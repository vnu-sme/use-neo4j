package org.uet.dse.neo4j.actions;

import org.uet.dse.neo4j.gui.SyncStatusDialog;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
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