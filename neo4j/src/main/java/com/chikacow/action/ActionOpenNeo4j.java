package com.chikacow.gui;


import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;

public class ActionOpenNeo4j implements IPluginAction {

    @Override
    public void performAction(Session session, MainWindow mainWindow) {
        // Đây là nơi kết nối code XML với code Java giao diện
        Neo4jMainDialog dialog = new Neo4jMainDialog(mainWindow, session);
        dialog.setVisible(true);
    }
}