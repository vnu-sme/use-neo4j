package com.chikacow.action;

import com.chikacow.gui.Neo4jConnectionDialog;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

public class ActionOpenNeo4j implements IPluginActionDelegate {

    @Override
    public void performAction(IPluginAction pluginAction) {
        Session session = pluginAction.getSession();
        MainWindow mainWindow = pluginAction.getParent();

        // Mở Dialog kết nối
        Neo4jConnectionDialog dialog = new Neo4jConnectionDialog(mainWindow, session);
        dialog.setVisible(true);
    }
}