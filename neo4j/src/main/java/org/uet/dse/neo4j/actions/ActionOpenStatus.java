package org.uet.dse.neo4j.actions;

import org.uet.dse.neo4j.gui.Neo4jStatusDialog;
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