package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.api.UseSystemApi;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.tgg.engine.NRuleCollectionManager;
import org.uet.dse.rtlplus.Main;

import javax.swing.*;

public class ActionRunTggFromNeo4j implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(pluginAction.getParent(),
                    "Please connect to Neo4j database first!", "Not Connected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (pluginAction.getSession() == null || !pluginAction.getSession().hasSystem()) {
            JOptionPane.showMessageDialog(pluginAction.getParent(),
                    "Please load a USE/RTL model first.", "No Active System", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (Main.getTggRuleCollection() == null || Main.getTggRuleCollection().getRuleList().isEmpty()) {
            JOptionPane.showMessageDialog(pluginAction.getParent(),
                    "No TGG rules are currently loaded. Parse the RTL/TGG files first.",
                    "No TGG Rules", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            NRuleCollectionManager manager =
                    new NRuleCollectionManager(UseSystemApi.create(pluginAction.getSession().system(), true));
            manager.iterateThroughMRuleCollection();
            JOptionPane.showMessageDialog(pluginAction.getParent(),
                    "Prototype TGG execution finished. Check the USE console/logs for details.",
                    "TGG Execution", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(pluginAction.getParent(),
                    "TGG execution failed: " + e.getMessage(),
                    "TGG Execution Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
}
