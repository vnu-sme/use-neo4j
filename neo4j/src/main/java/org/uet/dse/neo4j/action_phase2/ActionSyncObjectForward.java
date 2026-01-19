package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;

public class ActionSyncObjectForward implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        ObjectSyncCoordinator coordinator = new ObjectSyncCoordinator(pluginAction.getSession().system());
        // Tham số true = Hiển thị thống kê trước khi thực hiện (Manual Sync)
        coordinator.syncObjectsForward(true);
    }
}