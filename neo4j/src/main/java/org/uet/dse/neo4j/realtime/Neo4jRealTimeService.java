package org.uet.dse.neo4j.realtime;

import org.tzi.use.main.Session;
import org.tzi.use.main.ChangeEvent;
import org.tzi.use.main.ChangeListener;
import org.uet.dse.neo4j.config.SyncConfig;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.sync.ModelSyncCoordinator;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;

import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Neo4jRealTimeService implements ChangeListener, Session.EvaluatedStatementListener {

    private static Neo4jRealTimeService instance;
    private final Session session;
    // Executor đơn luồng đảm bảo tính tuần tự: Model xong mới đến Object
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Neo4jRealTimeService(Session session) {
        this.session = session;
    }

    public static synchronized void start(Session session) {
        if (instance != null) instance.stop();
        instance = new Neo4jRealTimeService(session);
        session.addChangeListener(instance);
        session.addEvaluatedStatementListener(instance);
        WorkLogManager.getInstance().log("REALTIME", "Service monitoring started.");
    }

    public void stop() {
        session.removeChangeListener(this);
        session.removeEvaluatedStatementListener(this);
        executor.shutdown();
    }

    @Override
    public void stateChanged(ChangeEvent var1) {
        // Khi nạp model mới (.use), ta sync cả Model và Object nếu được bật
        processSync(SyncConfig.getInstance().autoPushModelOnImport,
                SyncConfig.getInstance().autoPushObjectOnChange);
    }

    @Override
    public void evaluatedStatement(Session.EvaluatedStatement var1) {
        // Khi gõ lệnh shell, kiểm tra config để sync
        processSync(SyncConfig.getInstance().autoPushModelOnImport,
                SyncConfig.getInstance().autoPushObjectOnChange);
    }

    /**
     * Quy trình xử lý Real-time đồng bộ
     */
    private void processSync(boolean syncModel, boolean syncObject) {
        if (!syncModel && !syncObject) return;

        // Đẩy vào hàng đợi thực thi ngầm
        executor.submit(() -> {
            try {
                if (!session.hasSystem()) return;

                // --- 1. ĐỒNG BỘ MODEL REAL-TIME ---
                if (syncModel) {
                    // Khởi tạo Coordinator mới để lấy Model tươi nhất từ Session
                    ModelSyncCoordinator modelCoord = new ModelSyncCoordinator(session, null);
                    // Hàm này tự động so sánh, xóa node cũ ở DB và push node mới (Không hiện Pop-up)
                    modelCoord.syncForwardRealtime();
                }

                // --- 2. ĐỒNG BỘ OBJECT REAL-TIME ---
                if (syncObject) {
                    // Khởi tạo Coordinator mới cho tầng Object
                    ObjectSyncCoordinator objectCoord = new ObjectSyncCoordinator(session.system());
                    // Thực hiện sync Object tự động (Xóa link cũ, cập nhật giá trị mới)
                  //  objectCoord.syncObjectsForwardRealtime();
                }

            } catch (Exception e) {
                // Ghi log lỗi vào file nếu sync ngầm thất bại
                WorkLogManager.getInstance().log("REALTIME_SYNC_ERROR", e.getMessage());
            }
        });
    }
}