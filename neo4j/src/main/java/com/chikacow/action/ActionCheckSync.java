package com.chikacow.action;

import com.chikacow.gui.SyncStatusDialog;
import com.chikacow.manager.Neo4jDriverManager;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

import javax.swing.*;

public class ActionCheckSync implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(pluginAction.getParent(), "Not connected to Neo4j!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SyncStatusDialog dialog = new SyncStatusDialog(pluginAction.getParent(), pluginAction.getSession());
        dialog.setVisible(true);
    }
}