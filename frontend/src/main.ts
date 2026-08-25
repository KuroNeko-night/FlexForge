import { createApp } from 'vue';

import App from './App.vue';
import { onUnauthorized } from './api/client';
import { clearSession } from './auth/token';
import { registerBuiltins } from './registry/rendererRegistry';
import { router } from './router';

const app = createApp(App);

registerBuiltins();
onUnauthorized(() => {
  clearSession();
  void router.push({ name: 'login' });
});

app.use(router);
app.mount('#app');
