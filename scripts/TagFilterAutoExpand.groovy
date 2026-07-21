// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/issues/2926

/***************************************************************************

 Tag Filter Auto-Expand — makes the Tag panel's native filter reveal matches
 that sit inside collapsed branches.

 Problem: the Tag panel (the "Attributes and tags" side tab) already has a live
 filter field. But its tree (a FilterableJTree) prunes to matches + ancestors and
 only RESTORES the previously expanded paths — it never expands to reveal a match
 that was inside a collapsed branch. So with a large tag hierarchy, a matching tag
 in a folded branch stays hidden. (Verified on a real 53-tag map: filtering for a
 nested tag left it at row -1, i.e. invisible.)

 This script augments the existing native filter field: after each filter it expands
 the (now pruned) tree, so every match becomes visible. Expanding the filtered tree
 is safe because the filter already reduced it to matches + their ancestor paths.

 It adds NO UI. It hooks the native filter field and, on a timer slightly longer than
 the native 300ms debounce, expands the current tag tree. Empty filter = no expansion
 (so clearing the field never blows the whole hierarchy open).

 Works as an INIT script (no need for the panel to be open at startup): the native
 filter field is created eagerly with the mode controller, so it exists in the tabbed
 panel from startup even while the side panel is collapsed. We hook that stable field;
 the tag tree only has to exist when you actually type (by then the panel is open). If
 the tabbed panel is not ready yet, we retry a few times.

 Idempotent: running it again does nothing if already hooked (safe to run from init AND
 from a menu). To disable, remove it from init and restart.

 *****************************************************************/

// @ExecutionModes({ON_SINGLE_NODE="/menu_bar/euu"})

import groovy.transform.Field

import org.freeplane.core.ui.components.UITools
import org.freeplane.features.mode.Controller

import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener

@Field final String HANDLE_KEY = "TagFilterAutoExpandHandle"
// > the native 300ms filter debounce (TagPanelManager.filterTimer), so we expand AFTER
// the native filter has applied and restored the old (collapsed) expansion state.
@Field int expandDelayMs = 350
// init-timing safety: retry finding the tag field until the tabbed panel is built.
@Field int maxFindAttempts = 12
@Field int findRetryMs = 500


// invokeLater: as an init script this may run off the EDT and/or before the tabbed panel
// is built. Defer to the EDT; the retry inside covers the "not built yet" case.
SwingUtilities.invokeLater { installOrRetry(0) }
return


void installOrRetry(int attempt) {
    Object[] panel = findTagPanelField()
    if (panel == null) {
        if (attempt < maxFindAttempts) {
            Timer t = new Timer(findRetryMs, { ActionEvent e -> installOrRetry(attempt + 1) } as ActionListener)
            t.setRepeats(false)
            t.start()
        } else {
            status("Tag filter auto-expand: tag panel not available.")
        }
        return
    }
    hook((JTextField) panel[0], (Container) panel[1])
}

void hook(JTextField field, Container panelRoot) {
    if (field.getClientProperty(HANDLE_KEY) != null) {
        status("Tag filter auto-expand: already ON.")
        return
    }

    // one non-repeating timer, restarted on each keystroke; fires after the native filter.
    Timer timer = new Timer(expandDelayMs, { ActionEvent e ->
        if (field.getText().trim().isEmpty()) return   // don't expand the whole tree on clear
        JTree tree = findTagTree(panelRoot)
        if (tree != null) expandAll(tree)
    } as ActionListener)
    timer.setRepeats(false)

    DocumentListener listener = new DocumentListener() {
        @Override
        void insertUpdate(DocumentEvent e) { timer.restart() }
        @Override
        void removeUpdate(DocumentEvent e) { timer.restart() }
        @Override
        void changedUpdate(DocumentEvent e) { timer.restart() }
    }
    field.getDocument().addDocumentListener(listener)

    // removal handle (so a future version could toggle it off; also marks "already hooked")
    field.putClientProperty(HANDLE_KEY, { ->
        field.getDocument().removeDocumentListener(listener)
        timer.stop()
    })

    status("Tag filter auto-expand: ON — matches inside collapsed branches now reveal themselves.")
}


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Helpers ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// Finds the Tag panel's filter field WITHOUT requiring the panel to be visible.
// Navigates the tabbed panel object tree, locates the "Manage tag categories" button
// (language-independent — identified by its action class), and ascends to the tag sub-panel
// that holds it; the JTextField there is the native tag filter field. Returns [field, root].
// The returned root is the stable tag sub-panel; the tag tree (created lazily) is found
// within it at filter time.
Object[] findTagPanelField() {
    Container tabs = (Container) UITools.getFreeplaneTabbedPanel()
    if (tabs == null) return null
    AbstractButton manageButton = findManageTagButton(tabs)
    if (manageButton == null) return null

    Container ancestor = manageButton.getParent()
    while (ancestor != null) {
        JTextField field = (JTextField) findFirst(ancestor, JTextField.class)
        if (field != null) return [field, ancestor] as Object[]
        ancestor = ancestor.getParent()
    }
    return null
}

AbstractButton findManageTagButton(Container c) {
    for (Component comp : c.getComponents()) {
        if (comp instanceof AbstractButton) {
            Action a = ((AbstractButton) comp).getAction()
            if (a != null && a.getClass().getSimpleName() == "ManageTagCategoriesAction") return (AbstractButton) comp
        }
        if (comp instanceof Container) {
            AbstractButton r = findManageTagButton((Container) comp)
            if (r != null) return r
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

// Expands every row of the (already filtered) tree. Row count grows as rows expand, so we
// walk by index until it stops growing.
void expandAll(JTree tree) {
    int i = 0
    while (i < tree.getRowCount()) {
        tree.expandRow(i)
        i++
    }
}

void status(String msg) {
    try { Controller.currentController.viewController.out(msg) } catch (Throwable ignore) {}
}
