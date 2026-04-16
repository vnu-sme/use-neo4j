package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.gui.TggTransformationConsole;

public class ActionTggMatchingManagement implements IPluginActionDelegate {
  @Override
  public void performAction(IPluginAction pluginAction) {
    MainWindow mainWindow = pluginAction.getParent();
    org.tzi.use.main.Session useSession = pluginAction.getSession();

    TggTransformationConsole dialog = new TggTransformationConsole(mainWindow, useSession);
    dialog.setVisible(true);
  }
}