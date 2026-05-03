// 路由配置示例
import { createRouter, createWebHistory } from 'vue-router';
import ChatView from '../views/ChatView.vue';

const routes = [
  {
    path: '/',
    name: 'Chat',
    component: ChatView
  },
  // 其他路由可以在这里添加
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

export default router;