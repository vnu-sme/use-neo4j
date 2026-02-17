package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.sync.object.ObjectSyncCoordinator;

public class ActionSyncObjectBackward implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        ObjectSyncCoordinator coordinator = new ObjectSyncCoordinator(pluginAction.getSession().system());
        // Tham số true: Hiện thống kê những gì sẽ được kéo về trước khi thực hiện
        coordinator.syncObjectsBackward(true);
    }
}