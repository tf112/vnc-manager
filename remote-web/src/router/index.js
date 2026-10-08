import { createRouter, createWebHistory } from 'vue-router'

import RemoteResource from '../views/RemoteResource.vue'
import FileManager from '../views/FileManager.vue'
import RemoteDesktop from '../views/RemoteDesktop.vue'

const routes = [
  {
    path: '/',
    name: 'RemoteResource',
    component: RemoteResource
  },
  {
    path: '/files',
    name: 'FileManager',
    component: FileManager
  },
  {
    path: '/desktop/:clientId',
    name: 'RemoteDesktop',
    component: RemoteDesktop,
    props: true
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
