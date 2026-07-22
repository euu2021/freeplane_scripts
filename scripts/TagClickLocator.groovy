// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/issues/2926

/***************************************************************************

 Tag Click Locator — click a tag on a node in the map, and the tag is
 located and revealed in the Tag Tree (Attributes & Tags panel): the branches on its
 path expand and it gets selected + scrolled into view.

 Goal (issue #2926): as you code, seeing where each tag sits in the hierarchy helps
 you learn the tag system and notice when a tag would fit better elsewhere.

 How it works (all verified on the 1.13.x dev runtime):
   - Tags on nodes are clickable chips (MapViewIconListComponent). getIconAt(point)
     returns the TagIcon under the cursor, and TagIcon.getTag().qualifiedTag().getContent()
     gives the fully-qualified tag, e.g. "agenda::repetitivo".
   - The Tag Tree nodes expose the same qualified string via
     tagCategories.categorizedContent(treeNode), so matching is a direct string compare.
   - Single click on a tag is free (the native handler only uses double-click -> edit tags).

 One GLOBAL AWT mouse listener catches tag clicks anywhere (survives node/map rebuilds,
 no per-node wiring). It is passive: it does not consume the click, so normal behavior
 (selecting the node) still happens.

 Requires the "Attributes & Tags" panel to be open (the tree must exist). If it is
 closed, clicks are ignored.

 Run once = ON, run again = OFF.

 *****************************************************************/

// @ExecutionModes({ON_SINGLE_NODE="/menu_bar/euu"})

import groovy.transform.Field

import org.freeplane.features.mode.Controller

import javax.swing.*
import javax.swing.tree.TreePath

import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent

@Field final String LISTENER_KEY = "TagClickLocatorListener"


JRootPane anchor = findMainRootPane()
if (anchor == null) { status("Tag click locator: main window not found."); return }

// toggle OFF if already on
Object existing = anchor.getClientProperty(LISTENER_KEY)
if (existing != null) {
    Toolkit.getDefaultToolkit().removeAWTEventListener((AWTEventListener) existing)
    anchor.putClientProperty(LISTENER_KEY, null)
    status("Tag click locator: OFF")
    return
}

AWTEventListener listener = new AWTEventListener() {
    @Override
    void eventDispatched(AWTEvent event) {
        if (!(event instanceof MouseEvent)) return
        MouseEvent e = (MouseEvent) event
        if (e.getID() != MouseEvent.MOUSE_CLICKED) return
        if (e.getButton() != MouseEvent.BUTTON1 || e.getClickCount() != 1) return
        Component comp = e.getComponent()
        if (comp == null || !comp.getClass().getName().endsWith("MapViewIconListComponent")) return
        try {
            def icon = comp.getIconAt(e.getPoint())
            if (icon == null || !icon.getClass().getName().endsWith("TagIcon")) return
            String qualified = icon.getTag().qualifiedTag().getContent()
            revealInTagTree(qualified)
        } catch (Throwable ignore) {}
    }
}
Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
anchor.putClientProperty(LISTENER_KEY, listener)
status("Tag click locator: ON — click a tag on a node to locate it in the Tag Tree.")
return


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Reveal engine ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// Finds the tag whose qualified content matches, in the tag-categories tree, and reveals it
// in the panel's JTagTree: expands its path, selects it, scrolls it into view.
void revealInTagTree(String qualifiedContent) {
    JTree tree = findPanelTagTree()
    if (tree == null) return   // Attributes & Tags panel not open

    // A live filter would hide the target, so clear it before locating.
    clearActiveFilter(tree)

    def tagCategories = Controller.currentController.map.getIconRegistry().getTagCategories()
    def match = findNodeByContent(tagCategories, tagCategories.getRootNode(), qualifiedContent)
    if (match == null) return

    TreePath path = new TreePath(match.getPath())
    tree.scrollPathToVisible(path)   // expands ancestors so the node becomes visible
    tree.setSelectionPath(path)
}

// If the tag filter is active, clear it: drop the tree's filter predicate AND empty the
// native filter field (otherwise its debounce would re-apply the filter and re-hide the
// target). Does nothing when no filter is active.
void clearActiveFilter(JTree tree) {
    JTextField field = findFilterField(tree)
    boolean fieldHasText = field != null && !field.getText().trim().isEmpty()
    if (!fieldHasText) return
    field.setText("")                          // stops the native filter from re-applying
    try { tree.setFilter(null) } catch (Throwable ignore) {}   // clear immediately (sync)
}

// The native tag filter field lives above the tree in the same panel.
JTextField findFilterField(JTree tree) {
    Container ancestor = tree.getParent()
    while (ancestor != null) {
        JTextField field = (JTextField) findFirst(ancestor, JTextField.class)
        if (field != null) return field
        ancestor = ancestor.getParent()
    }
    return null
}

Component findFirst(Container c, Class<?> type) {
    for (Component comp : c.getComponents()) {
        if (type.isInstance(comp)) return comp
        if (comp instanceof Container) {
            Component r = findFirst((Container) comp, type)
            if (r != null) return r
        }
    }
    return null
}

// Depth-first search of the tag-categories tree for the node whose full (categorized)
// content equals the given qualified tag, e.g. "agenda::repetitivo".
Object findNodeByContent(Object tagCategories, Object node, String qualified) {
    for (int i = 0; i < node.getChildCount(); i++) {
        Object child = node.getChildAt(i)
        if (tagCategories.categorizedContent(child) == qualified) return child
        Object found = findNodeByContent(tagCategories, child, qualified)
        if (found != null) return found
    }
    return null
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Reveal engine ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Helpers ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// The tag tree in the main window (the Attributes & Tags panel), not the manager dialog.
JTree findPanelTagTree() {
    for (Window w : Window.getWindows()) {
        if (w.isShowing() && w instanceof JFrame) {
            JTree t = findTagTree(w)
            if (t != null) return t
        }
    }
    return null
}

JTree findTagTree(Container c) {
    for (Component comp : c.getComponents()) {
        if (comp.getClass().getName().endsWith("JTagTree")) return (JTree) comp
        if (comp instanceof Container) {
            JTree r = findTagTree((Container) comp)
            if (r != null) return r
        }
    }
    return null
}

JRootPane findMainRootPane() {
    for (Window w : Window.getWindows()) {
        if (w.isShowing() && w instanceof JFrame) return ((JFrame) w).getRootPane()
    }
    return null
}

void status(String msg) {
    try { Controller.currentController.viewController.out(msg) } catch (Throwable ignore) {}
}
