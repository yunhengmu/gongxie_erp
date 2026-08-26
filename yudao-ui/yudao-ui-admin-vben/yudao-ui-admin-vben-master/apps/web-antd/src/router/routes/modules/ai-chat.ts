import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/ai/chat-popup',
    name: 'AiChatPopup',
    component: () => import('#/views/ai/chat/standalone/index.vue'),
    meta: {
      title: 'AI 助手',
      hideInMenu: true,
      hideInTab: true,
      // 顶级路由，脱离基础布局（不显示后台的侧边栏/顶栏）
      noBasicLayout: true,
    },
  },
];

export default routes;