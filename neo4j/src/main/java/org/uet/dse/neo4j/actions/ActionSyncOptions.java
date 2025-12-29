package org.uet.dse.neo4j.actions;

import org.uet.dse.neo4j.gui.SyncOptionsDialog;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

public class ActionSyncOptions implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();
        // Mở Dialog chọn Option
        SyncOptionsDialog dialog = new SyncOptionsDialog(mainWindow, pluginAction.getSession());
        dialog.setVisible(true);
    }
}