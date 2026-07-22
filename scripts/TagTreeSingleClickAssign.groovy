// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/issues/2926

/***************************************************************************

 Tag Tree Single-Click Assign — assign a tag to the current node with a
 SINGLE left-click in the Tag Tree (Attributes & Tags panel), instead of a double-click.

 Rationale (issue #2926): in the Tag panel, a single left-click on a tag does nothing
 useful (expand/collapse is on the triangle); double-click is what assigns the tag to the
 selected node. Over a long coding session, repeating a double-click thousands of times is
 tiring. This makes the single click assign, so tagging is one click.

 How it works (mirrors the native double-click handler, TagPanelManager:137-147):
   - the tag under the click = tagCategories.categorizedTag(treeNodeAtPoint)
   - assign = MIconController.insertTagsIntoSelectedNodes([tag]) (assigns to the map's
     currently selected node(s); one undoable step)
 One GLOBAL AWT mouse listener catches clicks on the panel's JTagTree (survives the tree
 being rebuilt on map changes; no per-tree wiring). It is passive — the normal row selection
 still happens, and clicks on the expand triangle still expand/collapse (they fall outside a
 node's bounds, so getPathForLocation returns null and we skip).

 It listens to MOUSE_PRESSED, NOT MOUSE_CLICKED: a click with the tiniest mouse movement
 between press and release produces no MOUSE_CLICKED (Swing turns it into a drag, and the
 tag tree has drag support), so a CLICKED-based version silently missed some clicks even
 though the row got selected. MOUSE_PRESSED always fires. (Trade-off: starting to DRAG a
 tag also assigns it once — undoable, and rare in a click-to-tag workflow.)

 Scope: the panel tree only (a JTagTree inside the main JFrame), NOT the "Manage tag
 categories" dialog (that one is for editing the hierarchy, not assigning).

 The native double-click still works (it just becomes redundant). Run once = ON, again = OFF.

 *****************************************************************/

// @ExecutionModes({ON_SINGLE_NODE="/menu_bar/euu"})

import groovy.transform.Field

import org.freeplane.features.icon.IconController
import org.freeplane.features.icon.mindmapmode.MIconController
import org.freeplane.features.mode.Controller

import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent

@Field final String LISTENER_KEY = "TagTreeSingleClickAssignListener"


JRootPane anchor = findMainRootPane()
if (anchor == null) { status("Single-click assign: main window not found."); return }

// toggle OFF if already on
Object existing = anchor.getClientProperty(LISTENER_KEY)
if (existing != null) {
    Toolkit.getDefaultToolkit().removeAWTEventListener((AWTEventListener) existing)
    anchor.putClientProperty(LISTENER_KEY, null)
    status("Tag single-click assign: OFF")
    return
}

AWTEventListener listener = new AWTEventListener() {
    @Override
    void eventDispatched(AWTEvent event) {
        if (!(event instanceof MouseEvent)) return
        MouseEvent e = (MouseEvent) event
        if (e.getID() != MouseEvent.MOUSE_PRESSED) return
        if (e.getButton() != MouseEvent.BUTTON1 || e.getClickCount() != 1) return
        Component comp = e.getComponent()
        if (comp == null || !comp.getClass().getName().endsWith("JTagTree")) return
        // panel tree only, not the manager dialog
        Window w = SwingUtilities.getWindowAncestor(comp)
        if (!(w instanceof JFrame)) return
        try { assignTagAt((JTree) comp, e.getX(), e.getY()) } catch (Throwable ignore) {}
    }
}
Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
anchor.putClientProperty(LISTENER_KEY, listener)
status("Tag single-click assign: ON — click a tag to add it to the selected node.")
return


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Assign ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void assignTagAt(JTree tree, int x, int y) {
    TreePath path = tree.getPathForLocation(x, y)
    if (path == null) return   // click on the expand triangle or empty area -> ignore
    if (tree.getPathBounds(path) == null) return
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent()

    def tagCategories = Controller.currentController.map.getIconRegistry().getTagCategories()
    def tag = tagCategories.categorizedTag(node)
    if (tag == null) return

    ((MIconController) IconController.getController()).insertTagsIntoSelectedNodes([tag])
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Assign ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Helpers ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

JRootPane findMainRootPane() {
    for (Window w : Window.getWindows()) {
        if (w.isShowing() && w instanceof JFrame) return ((JFrame) w).getRootPane()
    }
    return null
}

void status(String msg) {
    try { Controller.currentController.viewController.out(msg) } catch (Throwable ignore) {}
}
