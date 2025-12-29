package org.uet.dse.neo4j.actions;

import org.uet.dse.neo4j.gui.ImportOptionsDialog;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

public class ActionImportFromNeo4j implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();
        ImportOptionsDialog dialog = new ImportOptionsDialog(mainWindow, pluginAction.getSession());
        dialog.setVisible(true);
    }
}