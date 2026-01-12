package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.sync.SyncCoordinator;
import org.uet.dse.neo4j.phase2.Neo4jUmlBridge;
import org.tzi.use.api.UseModelApi;

public class ActionSyncModelForward implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();
        SyncCoordinator coordinator = new SyncCoordinator(pluginAction.getSession(), mainWindow);
        // Thực hiện đồng bộ xuôi có kiểm tra xung đột (Manual Sync)
        coordinator.syncForward();
    }
}