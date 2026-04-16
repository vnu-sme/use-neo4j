package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.gui.TggTransformationDialog;

public class ActionTggTransformation implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();
        org.tzi.use.main.Session useSession = pluginAction.getSession();
        TggTransformationDialog dialog = new TggTransformationDialog(mainWindow, useSession);
        dialog.setVisible(true);
    }
}