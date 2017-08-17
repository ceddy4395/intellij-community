/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.ex

import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.actions.AnnotationsSettings
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.ChangeListWorker
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.ex.DocumentTracker.Block
import com.intellij.openapi.vcs.ex.PartialLocalLineStatusTracker.LocalRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.CalledInAwt
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Point
import java.util.*
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class PartialLocalLineStatusTracker(project: Project,
                                    document: Document,
                                    virtualFile: VirtualFile,
                                    mode: Mode
) : LineStatusTracker<LocalRange>(project, document, virtualFile, mode), ChangeListWorker.PartialChangeTracker {
  private val changeListManager = ChangeListManagerImpl.getInstanceImpl(project)

  override val renderer = MyLineStatusMarkerRenderer(this)

  private var defaultMarker: ChangeListMarker
  private var currentMarker: ChangeListMarker? = null

  private val affectedChangeLists = HashSet<String>()

  init {
    defaultMarker = ChangeListMarker(changeListManager.defaultChangeList)

    affectedChangeLists.add(defaultMarker.changelistId)

    documentTracker.addListener(MyLineTrackerListener())
    assert(blocks.isEmpty())
  }

  override fun Block.toRange(): LocalRange = LocalRange(this.start, this.end, this.vcsStart, this.vcsEnd, this.innerRanges,
                                                        this.marker.changelistId)


  override fun getAffectedChangeListsIds(): List<String> {
    return documentTracker.readLock {
      assert(!affectedChangeLists.isEmpty())
      affectedChangeLists.toList()
    }
  }

  private fun updateAffectedChangeLists(notifyChangeListManager: Boolean = true) {
    val oldIds = HashSet<String>()
    val newIds = HashSet<String>()

    for (block in blocks) {
      newIds.add(block.marker.changelistId)
    }

    if (newIds.isEmpty()) {
      if (affectedChangeLists.size == 1) {
        newIds.add(affectedChangeLists.single())
      }
      else {
        newIds.add(defaultMarker.changelistId)
      }
    }

    oldIds.addAll(affectedChangeLists)

    affectedChangeLists.clear()
    affectedChangeLists.addAll(newIds)

    if (notifyChangeListManager && oldIds != newIds) {
      // It's OK to call this under documentTracker.writeLock, as this method will not grab CLM lock.
      changeListManager.notifyChangelistsChanged()
    }
  }

  @CalledInAwt
  fun setBaseRevision(vcsContent: CharSequence, changelistId: String?) {
    currentMarker = if (changelistId != null) ChangeListMarker(changelistId) else null
    try {
      setBaseRevision(vcsContent)
    }
    finally {
      currentMarker = null
    }
  }


  override fun initChangeTracking(defaultId: String, changelistsIds: List<String>) {
    documentTracker.writeLock {
      defaultMarker = ChangeListMarker(defaultId)

      val idsSet = changelistsIds.toSet()
      moveMarkers({ !idsSet.contains(it.changelistId) }, defaultMarker)
    }
  }

  override fun defaultListChanged(oldListId: String, newListId: String) {
    documentTracker.writeLock {
      defaultMarker = ChangeListMarker(newListId)
    }
  }

  override fun changeListRemoved(listId: String) {
    documentTracker.writeLock {
      if (!affectedChangeLists.contains(listId)) return@writeLock

      moveMarkers({ it.changelistId == listId }, defaultMarker)

      if (affectedChangeLists.size == 1 && affectedChangeLists.contains(listId)) {
        affectedChangeLists.clear()
        affectedChangeLists.add(defaultMarker.changelistId)
      }
    }
  }

  override fun moveChangesTo(toListId: String) {
    documentTracker.writeLock {
      moveMarkers({ true }, ChangeListMarker(toListId))
    }
  }

  private fun moveMarkers(condition: (ChangeListMarker) -> Boolean, toMarker: ChangeListMarker) {
    val affectedBlocks = mutableListOf<Block>()

    for (block in blocks) {
      if (condition(block.marker)) {
        block.marker = toMarker
        affectedBlocks.add(block)
      }
    }

    updateAffectedChangeLists(false) // no need to notify CLM, as we're inside it's action

    for (block in affectedBlocks) {
      updateHighlighter(block)
    }
  }


  private inner class MyLineTrackerListener : DocumentTracker.Listener {
    override fun onRangeAdded(block: Block) {
      if (block.ourData.marker == null) { // do not override markers, that are set via other methods of this listener
        block.marker = defaultMarker
      }
    }

    override fun onRangeRefreshed(before: Block, after: List<Block>) {
      val marker = before.marker
      for (block in after) {
        block.marker = marker
      }
    }

    override fun onRangesChanged(before: List<Block>, after: Block) {
      val affectedMarkers = before.map { it.marker }.distinct()

      val _currentMarker = currentMarker
      if (affectedMarkers.isEmpty() && _currentMarker != null) {
        after.marker = _currentMarker
      }
      else if (affectedMarkers.size == 1) {
        after.marker = affectedMarkers.single()
      }
      else {
        after.marker = defaultMarker
      }
    }

    override fun onRangeShifted(before: Block, after: Block) {
      after.marker = before.marker
    }

    override fun afterRefresh() {
      updateAffectedChangeLists()
    }

    override fun afterRangeChange() {
      updateAffectedChangeLists()
    }

    override fun afterExplicitChange() {
      updateAffectedChangeLists()
    }
  }


  protected class MyLineStatusMarkerRenderer(override val tracker: PartialLocalLineStatusTracker) :
    LineStatusTracker.LocalLineStatusMarkerRenderer(tracker) {

    override fun paint(editor: Editor, range: Range, g: Graphics) {
      super.paint(editor, range, g)

      if (range is LocalRange) {
        val markerColor = getMarkerColor(editor, range)
        if (markerColor != null) {
          val area = getMarkerArea(editor, range.line1, range.line2)

          val extraHeight = if (area.height != 0) 0 else JBUI.scale(3)
          val width = JBUI.scale(2)
          val x = area.x + area.width - width
          val y = area.y - extraHeight
          val height = area.height + 2 * extraHeight

          g.color = markerColor
          g.fillRect(x, y, width, height)
        }
      }
    }

    private fun getMarkerColor(editor: Editor, range: LocalRange): Color? {
      if (range.changelistId == tracker.defaultMarker.changelistId) return null

      val colors = AnnotationsSettings.getInstance().getAuthorsColors(editor.colorsScheme)
      val seed = range.changelistId.hashCode()
      return colors[Math.abs(seed % colors.size)]
    }

    override fun createAdditionalInfoPanel(editor: Editor, range: Range): JComponent? {
      if (range !is LocalRange) return null

      val list = ChangeListManager.getInstance(tracker.project).getChangeList(range.changelistId) ?: return null

      val panel = JPanel(BorderLayout())
      panel.add(JLabel(list.name), BorderLayout.CENTER)
      panel.border = JBUI.Borders.emptyLeft(5)
      panel.isOpaque = false
      return panel
    }

    override fun createToolbarActions(editor: Editor, range: Range, mousePosition: Point?): List<AnAction> {
      val actions = ArrayList<AnAction>()
      actions.addAll(super.createToolbarActions(editor, range, mousePosition))
      actions.add(SetChangeListAction(editor, range, mousePosition))
      return actions
    }

    private inner class SetChangeListAction(val editor: Editor, range: Range, val mousePosition: Point?)
      : RangeMarkerAction(range, IdeActions.MOVE_TO_ANOTHER_CHANGE_LIST) {
      override fun isEnabled(range: Range): Boolean = range is LocalRange

      override fun actionPerformed(range: Range) {
        MoveChangesLineStatusAction.moveToAnotherChangelist(tracker, range as LocalRange)

        val newRange = tracker.findRange(range)
        if (newRange != null) tracker.renderer.showHintAt(editor, newRange, mousePosition)
      }
    }
  }


  @CalledInAwt
  fun moveToChangelist(range: Range, changelist: LocalChangeList) {
    documentTracker.writeLock {
      val block = findBlock(range)
      if (block != null) moveToChangelist(listOf(block), changelist)
    }
  }

  @CalledInAwt
  fun moveToChangelist(lines: BitSet, changelist: LocalChangeList) {
    documentTracker.writeLock {
      moveToChangelist(blocks.filter { it.isSelectedByLine(lines) }, changelist)
    }
  }

  @CalledInAwt
  private fun moveToChangelist(blocks: List<Block>, changelist: LocalChangeList) {
    val newMarker = ChangeListMarker(changelist)
    for (block in blocks) {
      if (block.marker != newMarker) {
        block.marker = newMarker
        updateHighlighter(block)
      }
    }

    updateAffectedChangeLists()
  }


  class LocalRange(line1: Int, line2: Int, vcsLine1: Int, vcsLine2: Int, innerRanges: List<InnerRange>?,
                   val changelistId: String)
    : Range(line1, line2, vcsLine1, vcsLine2, innerRanges)

  protected data class ChangeListMarker(val changelistId: String) {
    constructor(changelist: LocalChangeList) : this(changelist.id)
  }

  protected data class MyBlockData(var marker: ChangeListMarker? = null) : LineStatusTrackerBase.BlockData()

  override fun createBlockData(): BlockData = MyBlockData()
  override val Block.ourData: MyBlockData get() = getBlockData(this) as MyBlockData

  private var Block.marker: ChangeListMarker
    get() = this.ourData.marker!! // can be null in MyLineTrackerListener, until `onBlockAdded` is called
    set(value) {
      this.ourData.marker = value
    }

  companion object {
    @JvmStatic
    fun createTracker(project: Project,
                      document: Document,
                      virtualFile: VirtualFile,
                      mode: Mode): PartialLocalLineStatusTracker {
      return PartialLocalLineStatusTracker(project, document, virtualFile, mode)
    }


    @JvmStatic
    fun createTracker(project: Project,
                      document: Document,
                      virtualFile: VirtualFile,
                      mode: Mode,
                      events: List<DocumentEvent>): PartialLocalLineStatusTracker {
      val tracker = createTracker(project, document, virtualFile, mode)

      for (event in events.reversed()) {
        tracker.updateDocument(Side.LEFT) { vcsDocument ->
          vcsDocument.replaceString(event.offset, event.offset + event.newLength, event.oldFragment)
        }
      }

      return tracker
    }
  }
}
