package org.uet.dse.neo4j.migration;

import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.sys.MSystem;
import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;

public class ObjectMigrationManager {
    private FullObjectSnapshot backup;

    /**
     * Bước 1: Chụp ảnh trạng thái hiện tại của Java (Trước khi nạp model mới)
     */
    public void backupLocalState(MSystem system) {
        ObjectSyncCoordinator coordinator = new ObjectSyncCoordinator(system);
        this.backup = coordinator.getJavaObjectSnapshot();
        System.out.println("Migration: Backup created for " + backup.objects.size() + " objects.");
    }

    /**
     * Bước 2: Tái cấu trúc trạng thái (Sau khi model mới đã được nạp)
     * Chỉ nạp lại những Object/Attribute còn hợp lệ với "hiến pháp" mới.
     */
    public void restoreState(MSystem newSystem) {
        if (backup == null || backup.objects.isEmpty()) return;

        UseSystemApi api = UseSystemApi.create(newSystem, true);
        int restoredCount = 0;

        for (ObjectState os : backup.objects.values()) {
            // Kiểm tra: Class của object cũ có còn tồn tại trong model mới không?
            if (newSystem.model().getClass(os.className) != null) {
                try {
                    api.createObject(os.className, os.name);
                    // Phục hồi giá trị (Hàm này cần check attr tồn tại trong class mới)
                    restoreAttributes(api, os, newSystem);
                    restoredCount++;
                } catch (Exception e) {
                    System.err.println("Migration: Could not restore object " + os.name);
                }
            }
        }
        System.out.println("Migration: Successfully restored " + restoredCount + " objects.");
    }

    private void restoreAttributes(UseSystemApi api, ObjectState os, MSystem system) {
        // Logic tương tự applyChangesToUSEState nhưng có thêm bước check attr tồn tại trong Model M1 mới
        // ...
    }
}