package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.gui.OclEvaluatorDialog;

public class ActionOpenOclEvaluator implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();
        OclEvaluatorDialog dialog = new OclEvaluatorDialog(mainWindow, pluginAction.getSession());
        dialog.setVisible(true);
    }
}