package com.chikacow.action;

import com.chikacow.gui.Neo4jStatusDialog;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

public class ActionOpenStatus implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        // Mở Dialog trạng thái
        Neo4jStatusDialog dialog = new Neo4jStatusDialog(pluginAction.getParent());
        dialog.setVisible(true);
    }
}