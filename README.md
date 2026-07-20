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
- `experimental` — prototype / proof of concept

## Scripts

| Script | What it does | Tags |
|---|---|---|
| [applyCustomEdgeColors](applyCustomEdgeColors.groovy) | Applies the user-defined edge colors to the selected nodes and their whole branch, cycling through colors. | `styles` |
| [autoScrollModes](autoScrollModes.groovy) | Selection listeners that keep the current node positioned as you navigate (auto-scroll modes). | `navigation` `view` `listener` |
| [conditionalStyleByFilter](conditionalStyleByFilter.groovy) | Adds a conditional style to the nodes matched by a script filter. | `styles` `filter/search` |
| [connectedNodesNavigator](connectedNodesNavigator.groovy) | Popup listing every node connected to the selected one (with direction), for quick jumping. | `navigation` `connectors` `panel/GUI` |
| [ConnectedCluster](ConnectedCluster.groovy) | Selects the node plus every node linked to it by connectors. | `selection` `connectors` |
| [copyNodeIdWithHash](copyNodeIdWithHash.groovy) | Copies the selected node's ID to the clipboard with `#` prepended, ready to paste as an internal hyperlink. | `clipboard` `maps` |
| [duplicateNode](duplicateNode.groovy) | Duplicates the selected node(s), placing each copy right below its original. | `editing` |
| [foldSiblingsOfSelected](foldSiblingsOfSelected.groovy) | Navigation mode that folds the siblings of the selected node to keep focus on the current branch. | `fold/minimize` `navigation` `listener` |
| [initScriptsTool](initScriptsTool.groovy) | Runs every `//init` script found across your script folders and reports which ran. | `init` |
| [initScriptsToolBridge](initScriptsToolBridge.groovy) | Small bridge to drop in your profile's `scripts/init/` folder so `initScriptsTool` runs at startup. | `init` |
| [invertMapColors](invertMapColors.groovy) | Toggles the whole map between light and dark themes (styles + background). | `styles` |
| [jfaceTreeViewer](jfaceTreeViewer.groovy) | Prototype: shows the map as a tree with attribute columns (JXTreeTable) over the map. | `panel/GUI` `experimental` |
| [justifyTopicAlignment](justifyTopicAlignment.groovy) | Aligns each level automatically, XMind-style. | `view` `listener` |
| [leftRightTraversal](leftRightTraversal.groovy) | Tweaks Left/Right arrow-key behavior when traversing nodes. | `navigation` `selection` |
| [LiveFilterPanel](LiveFilterPanel.groovy) | Search panel that, on every keystroke, hides everything except the matches and their ancestor path (live filter over the whole map). | `filter/search` `panel/GUI` |
| [navigationLimitedToSiblingsDown](navigationLimitedToSiblingsDown.groovy) | Moves the selection down, but only among siblings (won't leave the level). | `navigation` `selection` |
| [navigationLimitedToSiblingsUP](navigationLimitedToSiblingsUP.groovy) | Moves the selection up, but only among siblings. | `navigation` `selection` |
| [noteFontSizeSlider](noteFontSizeSlider.groovy) | Slider dialog to quickly adjust the Note style's font size. | `styles` `panel/GUI` |
| [openLinkedMapAndClose](openLinkedMapAndClose.groovy) | Opens the map linked from the selected node and closes the current one (backlink navigation without piling up open maps). | `maps` `navigation` |
| [OutlineLiveFilter](OutlineLiveFilter.groovy) | Type-to-filter field docked in the Outline panel; filters the outline only, leaving the map untouched. | `filter/search` `panel/GUI` |
| [paste_and_minimize](paste_and_minimize.groovy) | Pastes, then auto-minimizes the just-pasted nodes that exceed the shortened-text length. | `clipboard` `fold/minimize` `editing` |
| [recentRootsNavigator_script1](recentRootsNavigator_script1.groovy) | Panel to navigate between recently used view-roots — a tabless jump-in workflow (main script). | `navigation` `maps` `panel/GUI` |
| [recentRootsNavigator_script2](recentRootsNavigator_script2.groovy) | Companion launcher for the Recent Roots Navigator (second shortcut). | `navigation` `maps` |
| [removeLastTag](removeLastTag.groovy) | Removes the last tag from the selected nodes. | `tags` `editing` |
| [resizeMapOverview](resizeMapOverview.groovy) | Resizes the Map Overview panel (the bird's-eye overview box) to any width/height you set. | `view` `panel/GUI` |
| [safeDeleteListener](safeDeleteListener.groovy) | Init listener that guards against accidental deletion of large branches. | `listener` `init` `editing` |
| [saveEventAutosave](saveEventAutosave.groovy) | Enables autosave and sets up a timer around the save event. | `listener` |
| [selectDOWN](selectDOWN.groovy) | Free-selects the node below and re-centers the view. | `navigation` `selection` |
| [selectLEFT](selectLEFT.groovy) | Free-selects the node to the left and re-centers the view. | `navigation` `selection` |
| [selectLevel1Children](selectLevel1Children.groovy) | Selects the visible first-level children of the selected nodes. | `selection` |
| [selectNodesByCloud](selectNodesByCloud.groovy) | Selects nodes by clicking on the clouds drawn around them. | `selection` |
| [selectRIGHT](selectRIGHT.groovy) | Free-selects the node to the right and re-centers the view. | `navigation` `selection` |
| [selectUP](selectUP.groovy) | Free-selects the node above and re-centers the view. | `navigation` `selection` |
| [selectionLimitedToSiblingsDown](selectionLimitedToSiblingsDown.groovy) | Extends the selection down, but only among siblings. | `selection` `navigation` |
| [selectionLimitedToSiblingsUp](selectionLimitedToSiblingsUp.groovy) | Extends the selection up, but only among siblings. | `selection` `navigation` |
| [setCreationDateToChildren](setCreationDateToChildren.groovy) | Sets a creation date on the selected nodes' children via a dialog. | `editing` |
| [simpleTagCreator](simpleTagCreator.groovy) | Creates a new tag and adds it to the selected node. | `tags` `editing` |
| [tagsImportFormat](tagsImportFormat.groovy) | Imports / normalizes a tag format into the node. | `tags` |
| [tagsWithFormulaListener](tagsWithFormulaListener.groovy) | Listener that generates a set of tags from a formula. | `tags` `listener` |
| [UtilityPanels](https://github.com/euu2021/Freeplane_UtilityPanels) ↗ | Collection of utility panels built into Freeplane's UI: quick search (with lines to results), recent nodes, breadcrumbs, in-place siblings preview, inspector tooltips, and more. Hosted in its own repository. | `panel/GUI` `filter/search` `navigation` |
| [zoomTo100](zoomTo100.groovy) | Sets the map zoom to 100%. | `view` |

## License

MIT — see `LICENSE`.
