package org.uet.dse.neo4j.actions;

import org.uet.dse.neo4j.gui.Neo4jQueryDialog;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

import javax.swing.*;

public class ActionOpenQueryConsole implements IPluginActionDelegate {

    @Override
    public void performAction(IPluginAction pluginAction) {
        MainWindow mainWindow = pluginAction.getParent();

        // 1. Kiểm tra kết nối trước
        if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(mainWindow, 
                "Chưa kết nối tới Neo4j!\nVui lòng vào Menu > Connection Settings để kết nối trước.", 
                "Chưa kết nối", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 2. Mở giao diện Query
        Neo4jQueryDialog dialog = new Neo4jQueryDialog(mainWindow);
        dialog.setVisible(true);
    }
}