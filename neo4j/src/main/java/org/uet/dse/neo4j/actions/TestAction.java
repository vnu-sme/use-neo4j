package org.uet.dse.neo4j.actions;

import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

public class TestAction implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {

    }

    @Override
    public boolean shouldBeEnabled(IPluginAction pluginAction) {
        return true;
    }
}
