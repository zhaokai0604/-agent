<template>
  <!--
    Element Plus ElMenu / ElMenuItem 在动态分组与 collapse 下卸载时，若 ref 解析与 vnode 拆除顺序重叠，
    易触发 getComponentPublicInstance(null).exposed。此处：稳定 key、shallowRef + 卸载清空、关闭折叠动画。
  -->
  <el-menu
    ref="menuRef"
    :key="structureKey"
    mode="vertical"
    :collapse="collapse"
    :collapse-transition="false"
    :default-active="activeTab"
    background-color="#001529"
    text-color="#fff"
    active-text-color="#409EFF"
    style="height: calc(100vh - 200px); border-right: none;"
    @select="onMenuSelect"
  >
    <el-menu-item-group
      v-for="(group, gi) in groups"
      :key="groupKey(group, gi)"
      :title="collapse ? '' : group.title"
    >
      <el-menu-item
        v-for="item in group.items"
        :key="itemKey(item)"
        :index="item.key"
      >
        <!-- 图标单独 key，避免与 ElMenuItem 内 Fragment 复用冲突 -->
        <el-icon><component :is="item.icon" :key="'icon-' + item.key" /></el-icon>
        <span v-if="!collapse">{{ item.label }}</span>
      </el-menu-item>
    </el-menu-item-group>
  </el-menu>
</template>

<script setup>
import { shallowRef, watch, nextTick, onBeforeUnmount } from 'vue'

const props = defineProps({
  groups: {
    type: Array,
    required: true
  },
  activeTab: {
    type: String,
    required: true
  },
  collapse: {
    type: Boolean,
    default: false
  },
  /** 与 App 侧 navMenuStructureKey 一致，结构变化时整菜单 remount */
  structureKey: {
    type: String,
    required: true
  }
})

const emit = defineEmits(['select'])

/** 组件实例用 shallowRef，避免深度响应式包裹子组件实例 */
const menuRef = shallowRef(null)

const groupKey = (group, gi) => `nav-grp-${gi}-${slug(group.title)}`
const itemKey = (item) => `nav-item-${item.key}`
const slug = (s) => String(s).replace(/\s+/g, '-').slice(0, 48)

const onMenuSelect = (index) => {
  emit('select', index)
}

/** 与 default-active 并行：在 DOM 稳定后再对齐一次，减少 EP 内部 watch 与插槽周期的错位 */
watch(
  () => props.activeTab,
  (v) => {
    nextTick(() => {
      const menu = menuRef.value
      if (menu && typeof menu.updateActiveIndex === 'function') {
        menu.updateActiveIndex(v)
      }
    })
  }
)

watch(
  () => props.structureKey,
  () => {
    nextTick(() => {
      const menu = menuRef.value
      if (menu && typeof menu.updateActiveIndex === 'function') {
        menu.updateActiveIndex(props.activeTab)
      }
    })
  }
)

onBeforeUnmount(() => {
  menuRef.value = null
})
</script>
