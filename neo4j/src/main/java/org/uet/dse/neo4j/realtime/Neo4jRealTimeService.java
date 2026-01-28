package org.uet.dse.neo4j.realtime;

import org.tzi.use.main.Session;
import org.tzi.use.main.ChangeEvent;
import org.tzi.use.main.ChangeListener;
import org.uet.dse.neo4j.config.SyncConfig;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.sync.ModelSyncCoordinator;
import org.uet.dse.neo4j.sync.ObjectDiff;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;

import java.util.concurrent.*;

public class Neo4jRealTimeService implements ChangeListener, Session.EvaluatedStatementListener {

    private static Neo4jRealTimeService instance;
    private final Session session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService pollScheduler = Executors.newScheduledThreadPool(2);

    // Lưu dấu vân tay của trạng thái Object local lần cuối cùng được đồng bộ
    private long lastLocalStateFingerprint = 0L;

    private Neo4jRealTimeService(Session session) {
        this.session = session;

        // JOB 1: Quét thay đổi từ SERVER (Neo4j) -> Để thực hiện AUTO-PULL
        pollScheduler.scheduleAtFixedRate(this::performAutoPull, 3, 3, TimeUnit.SECONDS);

        // JOB 2: Quét thay đổi từ LOCAL (USE GUI) -> Để thực hiện AUTO-PUSH
        // Quét mỗi 2 giây để đảm bảo độ nhạy khi người dùng thao tác GUI
        pollScheduler.scheduleAtFixedRate(this::performLocalScan, 2, 2, TimeUnit.SECONDS);
    }

    public static synchronized void start(Session session) {
        if (instance != null) instance.stop();
        instance = new Neo4jRealTimeService(session);
        session.addChangeListener(instance);
        session.addEvaluatedStatementListener(instance);
        WorkLogManager.getInstance().log("REALTIME", "Deep Scan Service Started");
    }

    /**
     * LUỒNG QUÉT LOCAL (Phát hiện thay đổi từ GUI của tool USE)
     */
    // Trong Neo4jRealTimeService.java

    private void performLocalScan() {
        if (!SyncConfig.getInstance().autoPushObjectOnChange || !session.hasSystem()) return;
        if (ObjectSyncCoordinator.isProcessingInternalUpdate()) return;

        try {
            long currentFingerprint = calculateLocalStateFingerprint();

            if (lastLocalStateFingerprint != 0 && currentFingerprint != lastLocalStateFingerprint) {
                System.out.println("Real-time: Identifying changed objects...");

                // 1. Tạo Coordinator để so sánh
                ObjectSyncCoordinator objectCoord = new ObjectSyncCoordinator(session.system());

                // 2. Lấy Diff để biết chính xác AI bị thay đổi
                ObjectDiff diff = objectCoord.compareObjects(
                        objectCoord.getJavaObjectSnapshot(),
                        objectCoord.getNeo4jObjectSnapshot()
                );

                // 3. CHỈ ĐẨY NHỮNG GÌ THAY ĐỔI (Incremental Push)
                // Việc này giúp p2 sẽ không bị quét trúng nếu bạn chỉ sửa p1
                if (diff.hasDifference()) {
                    objectCoord.executePush(diff);
                    System.out.println("Real-time: Syncing only modified entities.");
                }

                lastLocalStateFingerprint = currentFingerprint;
            }
            if (lastLocalStateFingerprint == 0) lastLocalStateFingerprint = currentFingerprint;

        } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * Tính toán Fingerprint (Dấu vân tay) dựa trên toàn bộ Objects và Values trong USE
     */
    private long calculateLocalStateFingerprint() {
        StringBuilder sb = new StringBuilder();
        var state = session.system().state();

        // Duyệt toàn bộ object và giá trị thuộc tính để tạo chuỗi đặc trưng
        state.allObjects().stream()
                .sorted((o1, o2) -> o1.name().compareTo(o2.name()))
                .forEach(obj -> {
                    sb.append(obj.name());
                    obj.state(state).attributeValueMap().forEach((attr, val) -> {
                        sb.append(attr.name()).append(val.toString());
                    });
                });

        // Cộng thêm số lượng Link hiện tại để bắt được sự kiện thêm/xóa Link
        sb.append("Links:").append(state.allLinks().size());

        return (long) sb.toString().hashCode();
    }

    private void performAutoPull() {
        if (SyncConfig.getInstance().autoPullObjectOnChange && session.hasSystem()) {
            try {
                ObjectSyncCoordinator objectCoord = new ObjectSyncCoordinator(session.system());
                if (!objectCoord.isQuickSyncCheck()) {
                    System.out.println("Auto-Pull: DB changed by another user. Syncing...");
                    objectCoord.syncObjectsBackwardRealtime();

                    // SAU KHI PULL XONG: Phải cập nhật lại dấu vân tay local ngay lập tức
                    // Để lần quét local sau không tưởng đây là thay đổi do người dùng làm
                    lastLocalStateFingerprint = calculateLocalStateFingerprint();
                }
            } catch (Exception e) { }
        }
    }

    @Override
    public void evaluatedStatement(Session.EvaluatedStatement var1) {
        // Vẫn giữ để Push tức thì khi dùng CLI, không cần đợi Job 2 giây
        if (SyncConfig.getInstance().autoPushObjectOnChange && !ObjectSyncCoordinator.isProcessingInternalUpdate()) {
            executor.submit(() -> {
                new ObjectSyncCoordinator(session.system()).syncObjectsForward(false);
                lastLocalStateFingerprint = calculateLocalStateFingerprint();
            });
        }
    }

    @Override
    public void stateChanged(ChangeEvent var1) {
        if (SyncConfig.getInstance().autoPushModelOnImport) {
            executor.submit(() -> new ModelSyncCoordinator(session, null).syncForwardRealtime());
        }
    }

    public void stop() {
        session.removeChangeListener(this);
        session.removeEvaluatedStatementListener(this);
        executor.shutdown();
        pollScheduler.shutdown();
    }
}