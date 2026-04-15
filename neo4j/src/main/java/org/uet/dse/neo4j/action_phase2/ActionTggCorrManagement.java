package org.uet.dse.neo4j.action_phase2;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.runtime.gui.IPluginAction;
import org.tzi.use.runtime.gui.IPluginActionDelegate;
import org.uet.dse.neo4j.gui.TggObjectManagerDialog;
import org.uet.dse.neo4j.gui.TggTransformationDialog;


import javax.swing.*;

public class ActionTggCorrManagement implements IPluginActionDelegate {
  @Override
  public void performAction(IPluginAction pluginAction) {
    MainWindow mainWindow = pluginAction.getParent();
    org.tzi.use.main.Session useSession = pluginAction.getSession();

    if (useSession.system().model() == null) {
      JOptionPane.showMessageDialog(mainWindow, "Vui lòng load model trong USE trước!");
      return;
    }

    String modelName = useSession.system().model().name();

    TggObjectManagerDialog dialog = new TggObjectManagerDialog(mainWindow, modelName);
    dialog.setVisible(true);
  }
}