package org.uet.dse.neo4j.actions;

import org.tzi.use.api.UseModelApi;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;

public class ActionDev implements IPluginActionDelegate {
    @Override
    public void performAction(IPluginAction pluginAction) {
        UseModelApi modelApi = new UseModelApi();
        System.out.println(modelApi.getModel().toString());
    }
}
