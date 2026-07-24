# freeplane_scripts

A collection of Groovy scripts for [Freeplane](https://www.freeplane.org/), by euu2021.

For more collections of scripts and add-on, see this page: [scripts collections](https://docs.freeplane.org/scripting/Finding_useful_scripts.html).

## How to use

See [Start here page](https://docs.freeplane.org/scripting/Start_Here.html).

Each file's header links to the forum thread it came from, if any.

## Tags

Scripts can belong to more than one category, so they are tagged. Ctrl-F a tag to find everything in that category.

- `navigation` — move the selection / jump around the map
- `selection` — select nodes
- `tags` — work with node tags
- `styles` — styles, colors, edges, themes
- `filter/search` — filtering and live search
- `panel/GUI` — floating panels, dialogs, sliders
- `maps` — multiple maps, view-roots, links between maps
- `editing` — create, duplicate or modify nodes
- `fold/minimize` — folding and minimizing nodes
- `clipboard` — clipboard operations
- `listener` — runs continuously, reacting to events
- `init` — runs once at startup
- `view` — zoom, layout, viewport
- `connectors` — works with connectors
- `sound` — audio feedback
- `experimental` — prototype / proof of concept

## Scripts

Sibling scripts (directional variants, script pairs) share a single row.

| Script | What it does | Tags |
|---|---|---|
| [applyCustomEdgeColors](scripts/applyCustomEdgeColors.groovy) | Applies the user-defined edge colors to the selected nodes and their whole branch, cycling through colors. | `styles` |
| [autoScrollModes](scripts/autoScrollModes.groovy) | Selection listeners that keep the current node positioned as you navigate (auto-scroll modes). | `navigation` `view` `listener` |
| [conditionalStyleByFilter](scripts/conditionalStyleByFilter.groovy) | Adds a conditional style to the nodes matched by a script filter. | `styles` `filter/search` |
| [connectedNodesNavigator](scripts/connectedNodesNavigator.groovy) | Popup listing every node connected to the selected one (with direction), for quick jumping. | `navigation` `connectors` `panel/GUI` |
| [ConnectedCluster](scripts/ConnectedCluster.groovy) | Selects the node plus every node linked to it by connectors. | `selection` `connectors` |
| [copyNodeIdWithHash](scripts/copyNodeIdWithHash.groovy) | Copies the selected node's ID to the clipboard with `#` prepended, ready to paste as an internal hyperlink. | `clipboard` `maps` |
| [CrossMapLinks](scripts/CrossMapLinks.groovy) | Panel showing the current map's cross-map links: OUT (this map links to), IN (backlinks / what links here), and Friends (connected maps you can browse in place, without opening them). Scans the active map's folder. | `maps` `navigation` `panel/GUI` |
| [duplicateNode](scripts/duplicateNode.groovy) | Duplicates the selected node(s), placing each copy right below its original. | `editing` |
| [foldSiblingsOfSelected](scripts/foldSiblingsOfSelected.groovy) | Navigation mode that folds the siblings of the selected node to keep focus on the current branch. | `fold/minimize` `navigation` `listener` |
| [initScriptsTool](scripts/initScriptsTool.groovy) · [Bridge](scripts/initScriptsToolBridge.groovy) | Runs every `//init` script found across your script folders and reports which ran; the *Bridge* is a small file you drop in your profile's `scripts/init/` folder so the tool runs at startup. | `init` |
| [invertMapColors](scripts/invertMapColors.groovy) | Toggles the whole map between light and dark themes (styles + background). | `styles` |
| [jfaceTreeViewer](scripts/jfaceTreeViewer.groovy) | Prototype: shows the map as a tree with attribute columns (JXTreeTable) over the map. | `panel/GUI` `experimental` |
| [justifyTopicAlignment](scripts/justifyTopicAlignment.groovy) | Aligns each level automatically, XMind-style. | `view` `listener` |
| [leftRightTraversal](scripts/leftRightTraversal.groovy) | Tweaks Left/Right arrow-key behavior when traversing nodes. | `navigation` `selection` |
| [LiveFilterPanel](scripts/LiveFilterPanel.groovy) | Search panel that, on every keystroke, hides everything except the matches and their ancestor path (live filter over the whole map). | `filter/search` `panel/GUI` |
| [LoremIpsumGenerator](scripts/LoremIpsumGenerator.groovy) | Fills the selected node with a random lorem ipsum subtree of a given size (1000 nodes by default): 1..10 children per node, mostly short texts with a thin long tail. For building large maps to test with. | `editing` |
| navigationLimitedToSiblings [Down](scripts/navigationLimitedToSiblingsDown.groovy) · [UP](scripts/navigationLimitedToSiblingsUP.groovy) | Moves the selection up/down, but only among siblings (won't leave the level). | `navigation` `selection` |
| [noteFontSizeSlider](scripts/noteFontSizeSlider.groovy) | Slider dialog to quickly adjust the Note style's font size. | `styles` `panel/GUI` |
| [openLinkedMapAndClose](scripts/openLinkedMapAndClose.groovy) | Opens the map linked from the selected node and closes the current one (backlink navigation without piling up open maps). | `maps` `navigation` |
| [OutlineLiveFilter](scripts/OutlineLiveFilter.groovy) | Type-to-filter field docked in the Outline panel; filters the outline only, leaving the map untouched. | `filter/search` `panel/GUI` |
| [paste_and_minimize](scripts/paste_and_minimize.groovy) | Pastes, then auto-minimizes the just-pasted nodes that exceed the shortened-text length. | `clipboard` `fold/minimize` `editing` |
| recentRootsNavigator [script1](scripts/recentRootsNavigator_script1.groovy) · [script2](scripts/recentRootsNavigator_script2.groovy) | The Recent Roots Navigator — a tabless jump-in workflow to move between recently used view-roots (main panel + its launcher shortcut). | `navigation` `maps` `panel/GUI` |
| [removeLastTag](scripts/removeLastTag.groovy) | Removes the last tag from the selected nodes. | `tags` `editing` |
| [resizeMapOverview](scripts/resizeMapOverview.groovy) | Resizes the Map Overview panel (the bird's-eye overview box) to any width/height you set. | `view` `panel/GUI` |
| [safeDeleteListener](scripts/safeDeleteListener.groovy) | Init listener that guards against accidental deletion of large branches. | `listener` `init` `editing` |
| [saveEventAutosave](scripts/saveEventAutosave.groovy) | Enables autosave and sets up a timer around the save event. | `listener` |
| select [DOWN](scripts/selectDOWN.groovy) · [UP](scripts/selectUP.groovy) · [LEFT](scripts/selectLEFT.groovy) · [RIGHT](scripts/selectRIGHT.groovy) | Free-selects the node in the given direction and re-centers the view. | `navigation` `selection` |
| [selectLevel1Children](scripts/selectLevel1Children.groovy) | Selects the visible first-level children of the selected nodes. | `selection` |
| [selectNodesByCloud](scripts/selectNodesByCloud.groovy) | Selects nodes by clicking on the clouds drawn around them. | `selection` |
| selectionLimitedToSiblings [Down](scripts/selectionLimitedToSiblingsDown.groovy) · [Up](scripts/selectionLimitedToSiblingsUp.groovy) | Extends the selection up/down, but only among siblings. | `selection` `navigation` |
| [setCreationDateToChildren](scripts/setCreationDateToChildren.groovy) | Sets a creation date on the selected nodes' children via a dialog. | `editing` |
| [simpleTagCreator](scripts/simpleTagCreator.groovy) | Creates a new tag and adds it to the selected node. | `tags` `editing` |
| [SoundEffects](scripts/SoundEffects.groovy) | Plays a short sound when a node is selected, created, moved or deleted, and when a branch is folded or unfolded. Each action has its own switch, and an action switched off is never installed. The sounds are synthesized in memory, so no audio file is needed — three themes are available (wooden chess pieces, dungeon, plain bleeps), and your own `.wav` files replace them if you prefer. Running the script again switches the sounds off. | `sound` `listener` |
| [TagCategoryDialogFilter](scripts/TagCategoryDialogFilter.groovy) | Docks a live filter field in the "Manage tag categories" dialog; each keystroke filters the tag tree and auto-expands it, so matches inside collapsed branches show up. | `tags` `filter/search` `panel/GUI` |
| [TagClickLocator](scripts/TagClickLocator.groovy) | Click a tag on a node in the map and it is located in the Tag Tree: the branches on its path expand and it is selected and scrolled into view (clears an active tag filter first). | `tags` `navigation` `panel/GUI` |
| [TagFilterAutoExpand](scripts/TagFilterAutoExpand.groovy) | Makes the Tag panel's native filter auto-expand, so matching tags inside collapsed branches become visible (they stay hidden otherwise). Works as a menu or startup (init) script. | `tags` `filter/search` `init` |
| [tagsImportFormat](scripts/tagsImportFormat.groovy) | Imports / normalizes a tag format into the node. | `tags` |
| [tagsWithFormulaListener](scripts/tagsWithFormulaListener.groovy) | Listener that generates a set of tags from a formula. | `tags` `listener` |
| [TagTreeSingleClickAssign](scripts/TagTreeSingleClickAssign.groovy) | Assign a tag to the selected node with a single left-click in the Tag Tree, instead of the default double-click. | `tags` `editing` |
| [UtilityPanels](https://github.com/euu2021/Freeplane_UtilityPanels) ↗ | Collection of utility panels built into Freeplane's UI: quick search (with lines to results), recent nodes, breadcrumbs, in-place siblings preview, inspector tooltips, and more. Hosted in its own repository. | `panel/GUI` `filter/search` `navigation` |
| [zoomTo100](scripts/zoomTo100.groovy) | Sets the map zoom to 100%. | `view` |


