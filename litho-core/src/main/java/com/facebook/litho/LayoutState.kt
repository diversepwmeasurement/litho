/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho

import android.graphics.Rect
import android.util.Pair
import androidx.annotation.VisibleForTesting
import androidx.collection.LongSparseArray
import com.facebook.litho.EndToEndTestingExtension.EndToEndTestingExtensionInput
import com.facebook.litho.LithoViewAttributesExtension.ViewAttributesInput
import com.facebook.litho.Transition.RootBoundsTransition
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.transition.TransitionData
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderTree
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.SizeConstraints.Helper.getHeightSpec
import com.facebook.rendercore.SizeConstraints.Helper.getWidthSpec
import com.facebook.rendercore.Systracer
import com.facebook.rendercore.incrementalmount.IncrementalMountExtensionInput
import com.facebook.rendercore.incrementalmount.IncrementalMountOutput
import com.facebook.rendercore.transitions.TransitionsExtensionInput
import com.facebook.rendercore.visibility.VisibilityBoundsTransformer
import com.facebook.rendercore.visibility.VisibilityExtensionInput
import com.facebook.rendercore.visibility.VisibilityOutput
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.CheckReturnValue

/**
 * The main role of [LayoutState] is to hold the output of layout calculation. This includes
 * mountable outputs and visibility outputs. A centerpiece of the class is
 * [LithoReducer#setSizeAfterMeasureAndCollectResults(ComponentContext, LithoLayoutContext, LayoutState)]
 * which prepares the before-mentioned outputs based on the provided [LithoNode] for later use in
 * [MountState].
 *
 * @property componentTreeId the id of the [ComponentTree] that generated this [LayoutState]
 *
 * This needs to be accessible to statically mock the class in tests.
 */
class LayoutState
internal constructor(
    val resolveResult: ResolveResult,
    val sizeConstraints: SizeConstraints,
    val componentTreeId: Int,
    val isAccessibilityEnabled: Boolean,
    val layoutCacheData: Map<Any, Any?>?,
    // needed to be var as it's being reset via consumeCreatedEventHandlers
    private var createdEventHandlers: List<Pair<String, EventHandler<*>>>?,
    reductionState: ReductionState
) :
    IncrementalMountExtensionInput,
    VisibilityExtensionInput,
    TransitionsExtensionInput,
    EndToEndTestingExtensionInput,
    PotentiallyPartialResult,
    ViewAttributesInput,
    DynamicPropsExtensionInput {

  private val animatableItems: LongSparseArray<AnimatableItem> = reductionState.animatableItems
  private val visibilityOutputs: List<VisibilityOutput> = reductionState.visibilityOutputs
  private val outputsIdToPositionMap: LongSparseArray<Int> = reductionState.outputsIdToPositionMap
  private val incrementalMountOutputs: Map<Long, IncrementalMountOutput> =
      reductionState.incrementalMountOutputs
  private val mountableOutputTops: ArrayList<IncrementalMountOutput> =
      reductionState.mountableOutputTops
  private val mountableOutputBottoms: ArrayList<IncrementalMountOutput> =
      reductionState.mountableOutputBottoms
  private val renderUnitIdsWhichHostRenderTrees: Set<Long> =
      reductionState.renderUnitIdsWhichHostRenderTrees
  private val tracer = ComponentsSystrace.systrace
  private val testOutputs: List<TestOutput>? = reductionState.testOutputs
  private val rootTransitionId: TransitionId? =
      LithoNodeUtils.createTransitionId(resolveResult.node)
  private val workingRangeContainer: WorkingRangeContainer? = reductionState.workingRangeContainer
  private val transitionIdMapping: Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> =
      reductionState.transitionIdMapping

  internal val transitionData: TransitionData? = reductionState.transitionData
  internal val scopedComponentInfosNeedingPreviousRenderData: List<ScopedComponentInfo>? =
      reductionState.scopedComponentInfosNeedingPreviousRenderData

  val root: LithoNode? = reductionState.rootNode
  val diffTree: DiffNode? = reductionState.diffTreeRoot
  val mountableOutputs: List<RenderTreeNode> = reductionState.mountableOutputs

  val componentKeyToBounds: Map<String, Rect> = reductionState.componentKeyToBounds
  val componentHandleToBounds: Map<Handle, Rect> = reductionState.componentHandleToBounds

  val width: Int = reductionState.width
  val height: Int = reductionState.height
  /** Id of this [LayoutState]. */
  val id: Int = reductionState.id
  /** Id of the [LayoutState] that was compared to when calculating this [LayoutState]. */
  val previousLayoutStateId: Int = reductionState.previousLayoutStateId
  val currentTransitionId: TransitionId? = reductionState.currentTransitionId
  val attachables: List<Attachable>? = reductionState.attachables
  /** Whether or not there are components marked as 'ExcludeFromIncrementalMount'. */
  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  val hasComponentsExcludedFromIncrementalMount: Boolean =
      reductionState.hasComponentsExcludedFromIncrementalMount
  val currentLayoutOutputAffinityGroup: OutputUnitsAffinityGroup<AnimatableItem>? =
      reductionState.currentLayoutOutputAffinityGroup

  val rootComponent: Component
    get() = resolveResult.component

  val componentHandles: Set<Handle>
    get() = componentHandleToBounds.keys

  val isEmpty: Boolean
    get() = resolveResult.component is EmptyComponent

  val isActivityValid: Boolean
    get() = ContextUtils.getValidActivityForContext(resolveResult.context.androidContext) != null

  val visibilityOutputCount: Int
    get() = visibilityOutputs.size

  val componentContext: ComponentContext
    get() = resolveResult.context

  /**
   * Returns the state handler instance currently held by LayoutState.
   *
   * @return the state handler
   */
  @get:CheckReturnValue
  val treeState: TreeState
    get() = resolveResult.treeState

  override val testOutputCount: Int
    get() = testOutputs?.size ?: 0

  override val viewAttributes: Map<Long, ViewAttributes> =
      reductionState.renderUnitsWithViewAttributes
  override val dynamicValueOutputs: Map<Long, DynamicValueOutput> =
      reductionState.dynamicValueOutputs
  override val isPartialResult: Boolean = false

  private var cachedRenderTree: RenderTree? = null // memoized RenderTree
  // TODO(t66287929): Remove isCommitted from LayoutState by matching RenderState logic around
  //  Futures.
  private var isCommitted = false
  // needed to be var as it's being updated in setShouldProcessVisibilityOutputs
  private var shouldProcessVisibilityOutputs = false
  // needed to be var as it's being reset via consumeScopedSpecComponentInfos
  private var scopedSpecComponentInfos: List<ScopedComponentInfo>? =
      reductionState.scopedSpecComponentInfos

  // needed to be var as a previously evaluated reference is set(restored) in LithoViewTestHelper
  var rootLayoutResult: LayoutResult? = reductionState.layoutResult
    internal set

  // needed to be var as it's being updated in setInitialRootBoundsForAnimation
  var rootWidthAnimation: RootBoundsTransition? = null
    private set

  // needed to be var as it's being updated in setInitialRootBoundsForAnimation
  var rootHeightAnimation: RootBoundsTransition? = null
    private set

  fun consumeScopedSpecComponentInfos(): List<ScopedComponentInfo>? {
    val scopedSpecComponentInfos = scopedSpecComponentInfos
    this.scopedSpecComponentInfos = null
    return scopedSpecComponentInfos
  }

  fun consumeCreatedEventHandlers(): List<Pair<String, EventHandler<*>>>? {
    val createdEventHandlers = createdEventHandlers
    this.createdEventHandlers = null
    return createdEventHandlers
  }

  fun toRenderTree(): RenderTree {
    cachedRenderTree?.let {
      return it
    }
    val root = mountableOutputs[0]
    check(root.renderUnit.id == MountState.ROOT_HOST_ID) {
      "Root render unit has invalid id ${root.renderUnit.id}"
    }
    val flatList = Array(mountableOutputs.size) { i -> mountableOutputs[i] }
    val renderTree =
        RenderTree.create(
            root,
            flatList,
            if (componentContext.mLithoConfiguration.componentsConfig.shouldReuseIdToPositionMap)
                outputsIdToPositionMap
            else null,
            sizeConstraints.encodedValue,
            componentTreeId,
            null,
            null)
    cachedRenderTree = renderTree
    return renderTree
  }

  fun isCompatibleSpec(widthSpec: Int, heightSpec: Int): Boolean {
    val widthIsCompatible =
        MeasureComparisonUtils.isMeasureSpecCompatible(
            getWidthSpec(sizeConstraints), widthSpec, width)
    val heightIsCompatible =
        MeasureComparisonUtils.isMeasureSpecCompatible(
            getHeightSpec(sizeConstraints), heightSpec, height)
    return widthIsCompatible && heightIsCompatible
  }

  fun isCompatibleComponentAndSpec(componentId: Int, widthSpec: Int, heightSpec: Int): Boolean =
      resolveResult.component.id == componentId && isCompatibleSpec(widthSpec, heightSpec)

  fun isCompatibleSize(width: Int, height: Int): Boolean =
      this.width == width && this.height == height

  fun isForComponentId(componentId: Int): Boolean = resolveResult.component.id == componentId

  override fun getMountableOutputCount(): Int = mountableOutputs.size

  override fun getIncrementalMountOutputCount(): Int = incrementalMountOutputs.size

  override fun getMountableOutputAt(position: Int): RenderTreeNode = mountableOutputs[position]

  override fun getIncrementalMountOutputForId(id: Long): IncrementalMountOutput? =
      incrementalMountOutputs[id]

  override fun getIncrementalMountOutputs(): Collection<IncrementalMountOutput> =
      incrementalMountOutputs.values

  override fun getAnimatableRootItem(): AnimatableItem? = animatableItems[MountState.ROOT_HOST_ID]

  override fun getAnimatableItem(id: Long): AnimatableItem? = animatableItems[id]

  override fun getOutputsOrderedByTopBounds(): List<IncrementalMountOutput> = mountableOutputTops

  override fun getOutputsOrderedByBottomBounds(): List<IncrementalMountOutput> =
      mountableOutputBottoms

  fun getVisibilityOutputAt(index: Int): VisibilityOutput = visibilityOutputs[index]

  override fun getVisibilityOutputs(): List<VisibilityOutput> = visibilityOutputs

  override fun getTestOutputAt(position: Int): TestOutput? = testOutputs?.get(position)

  fun getWidthSpec(): Int = getWidthSpec(sizeConstraints)

  fun getHeightSpec(): Int = getHeightSpec(sizeConstraints)

  override fun getTreeId(): Int = componentTreeId

  // If the layout root is a nested tree holder node, it gets skipped immediately while
  // collecting the LayoutOutputs. The nested tree itself effectively becomes the layout
  // root in this case.
  fun isLayoutRoot(result: LithoLayoutResult): Boolean =
      if (rootLayoutResult is NestedTreeHolderResult)
          result === (rootLayoutResult as NestedTreeHolderResult).nestedResult
      else result === rootLayoutResult

  /**
   * @return the position of the [LithoRenderUnit] with id layoutOutputId in the [LayoutState] list
   *   of outputs or -1 if no [LithoRenderUnit] with that id exists in the [LayoutState]
   */
  override fun getPositionForId(id: Long): Int = checkNotNull(outputsIdToPositionMap.get(id, -1))

  override fun renderUnitWithIdHostsRenderTrees(id: Long): Boolean =
      renderUnitIdsWhichHostRenderTrees.contains(id)

  override fun getRenderUnitIdsWhichHostRenderTrees(): Set<Long> = renderUnitIdsWhichHostRenderTrees

  override fun getTransitions(): List<Transition>? = transitionData?.transitions

  /** Gets a mapping from transition ids to a group of LayoutOutput. */
  override fun getTransitionIdMapping():
      Map<TransitionId, OutputUnitsAffinityGroup<AnimatableItem>> = transitionIdMapping

  /** Gets a group of LayoutOutput given transition key */
  override fun getAnimatableItemForTransitionId(
      transitionId: TransitionId
  ): OutputUnitsAffinityGroup<AnimatableItem>? = transitionIdMapping[transitionId]

  override fun setInitialRootBoundsForAnimation(
      rootWidth: RootBoundsTransition?,
      rootHeight: RootBoundsTransition?
  ) {
    rootWidthAnimation = rootWidth
    rootHeightAnimation = rootHeight
  }

  override fun getMountTimeTransitions(): List<Transition>? {
    val state = resolveResult.treeState
    state.applyPreviousRenderData(this)
    var mountTimeTransitions: MutableList<Transition>? = null
    if (scopedComponentInfosNeedingPreviousRenderData != null) {
      mountTimeTransitions = ArrayList()
      for (scopedComponentInfo in scopedComponentInfosNeedingPreviousRenderData) {
        val scopedContext = scopedComponentInfo.context
        val component = scopedComponentInfo.component
        try {
          val transition = (component as? SpecGeneratedComponent)?.createTransition(scopedContext)
          if (transition != null) {
            mountTimeTransitions.add(transition)
          }
        } catch (e: Exception) {
          ComponentUtils.handleWithHierarchy(scopedContext, component, e)
        }
      }
    }
    val mountedLayoutStateData = state.getPreviousLayoutStateData()
    if (transitionData != null && !transitionData.isEmpty()) {
      if (mountTimeTransitions == null) {
        mountTimeTransitions = ArrayList()
      }
      if (previousLayoutStateId == mountedLayoutStateData.layoutStateId) {
        val optimisticTransitions = transitionData.optimisticTransitions
        if (optimisticTransitions != null) {
          mountTimeTransitions.addAll(optimisticTransitions)
        }
      } else {
        val twds = transitionData.transitionsWithDependency
        if (twds != null) {
          for ((_, twd) in twds) {
            val previousTwd = mountedLayoutStateData.getTransitionWithDependency(twd.identityKey)
            val transition = twd.createTransition(previousTwd)
            if (transition != null) {
              mountTimeTransitions.add(transition)
            }
          }
        }
      }
    }
    val updateStateTransitions = state.pendingStateUpdateTransitions
    if (updateStateTransitions.isNotEmpty()) {
      if (mountTimeTransitions == null) {
        mountTimeTransitions = ArrayList()
      }
      mountTimeTransitions.addAll(updateStateTransitions)
    }
    return mountTimeTransitions
  }

  override fun isIncrementalMountEnabled(): Boolean =
      ComponentContext.isIncrementalMountEnabled(resolveResult.context)

  override fun getTracer(): Systracer = tracer

  override fun getRootTransitionId(): TransitionId? = rootTransitionId

  /** Debug-only: return a string representation of this LayoutState and its LayoutOutputs. */
  fun dumpAsString(): String {
    if (!LithoDebugConfigurations.isDebugModeEnabled &&
        !ComponentsConfiguration.isEndToEndTestRun) {
      throw RuntimeException(
          "LayoutState#dumpAsString() should only be called in debug mode or from e2e tests!")
    }
    var res =
        """LayoutState w/ ${getMountableOutputCount()} mountable outputs, root: ${resolveResult.component}
"""
    for (i in 0 until getMountableOutputCount()) {
      val node = getMountableOutputAt(i)
      val renderUnit = LithoRenderUnit.getRenderUnit(node)
      res +=
          """  [$i] id: ${node.renderUnit.id}, host: ${node.parent?.renderUnit?.id ?: -1}, component: ${renderUnit.component.simpleName}
"""
    }
    return res
  }

  fun checkWorkingRangeAndDispatch(
      position: Int,
      firstVisibleIndex: Int,
      lastVisibleIndex: Int,
      firstFullyVisibleIndex: Int,
      lastFullyVisibleIndex: Int,
      stateHandler: WorkingRangeStatusHandler
  ) {
    if (workingRangeContainer == null) {
      return
    }
    workingRangeContainer.checkWorkingRangeAndDispatch(
        position,
        firstVisibleIndex,
        lastVisibleIndex,
        firstFullyVisibleIndex,
        lastFullyVisibleIndex,
        stateHandler)
  }

  fun dispatchOnExitRangeIfNeeded(stateHandler: WorkingRangeStatusHandler) {
    if (workingRangeContainer == null) {
      return
    }
    workingRangeContainer.dispatchOnExitedRangeIfNeeded(stateHandler)
  }

  override fun needsToRerunTransitions(): Boolean {
    val stateUpdater = resolveResult.context.stateUpdater
    return stateUpdater?.isFirstMount == true
  }

  override fun setNeedsToRerunTransitions(needsToRerunTransitions: Boolean) {
    val stateUpdater = resolveResult.context.stateUpdater
    if (stateUpdater != null) {
      stateUpdater.isFirstMount = needsToRerunTransitions
    }
  }

  fun isCommitted(): Boolean = isCommitted

  fun markCommitted() {
    isCommitted = true
  }

  override fun isProcessingVisibilityOutputsEnabled(): Boolean = shouldProcessVisibilityOutputs

  fun setShouldProcessVisibilityOutputs(value: Boolean) {
    shouldProcessVisibilityOutputs = value
  }

  override fun getRootName(): String = resolveResult.component.simpleName

  fun getRenderTreeNode(output: IncrementalMountOutput): RenderTreeNode =
      getMountableOutputAt(output.index)

  override fun getVisibilityBoundsTransformer(): VisibilityBoundsTransformer? =
      componentContext.visibilityBoundsTransformer

  companion object {
    @JvmStatic
    fun isFromSyncLayout(@RenderSource source: Int): Boolean =
        when (source) {
          RenderSource.MEASURE_SET_SIZE_SPEC,
          RenderSource.SET_ROOT_SYNC,
          RenderSource.UPDATE_STATE_SYNC,
          RenderSource.SET_SIZE_SPEC_SYNC -> true
          else -> false
        }

    @get:JvmStatic val idGenerator: AtomicInteger = AtomicInteger(1)

    const val NO_PREVIOUS_LAYOUT_STATE_ID: Int = -1

    @JvmStatic
    fun layoutSourceToString(@RenderSource source: Int): String =
        when (source) {
          RenderSource.SET_ROOT_SYNC -> "setRootSync"
          RenderSource.SET_SIZE_SPEC_SYNC -> "setSizeSpecSync"
          RenderSource.UPDATE_STATE_SYNC -> "updateStateSync"
          RenderSource.SET_ROOT_ASYNC -> "setRootAsync"
          RenderSource.SET_SIZE_SPEC_ASYNC -> "setSizeSpecAsync"
          RenderSource.UPDATE_STATE_ASYNC -> "updateStateAsync"
          RenderSource.MEASURE_SET_SIZE_SPEC -> "measure_setSizeSpecSync"
          RenderSource.MEASURE_SET_SIZE_SPEC_ASYNC -> "measure_setSizeSpecAsync"
          RenderSource.TEST -> "test"
          RenderSource.NONE -> "none"
          else -> throw RuntimeException("Unknown calculate layout source: $source")
        }

    @JvmStatic
    @VisibleForTesting
    @OutputUnitType
    fun getTypeFromId(id: Long): Int {
      val masked = id and -0x100000000L
      return (masked shr 32).toInt()
    }

    @JvmStatic
    fun isNullOrEmpty(layoutState: LayoutState?): Boolean =
        layoutState == null || layoutState.isEmpty
  }
}