package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.sync.SyncCoordinator;

public class ActionSyncModelBackward implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();
        SyncCoordinator coordinator = new SyncCoordinator(pluginAction.getSession(), mainWindow);
        coordinator.syncBackward();
    }
}