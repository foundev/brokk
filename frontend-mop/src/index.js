import { mount } from 'svelte';
import { writable } from 'svelte/store';
import App from './App.svelte';

// Entry point for Brokk frontend
console.log('Brokk frontend initialized');

// Create a writable store for events
const eventStore = writable({ type: '' });

// Instantiate the app using Svelte 5 API
const app = mount(App, {
  target: document.body,
  props: { eventStore }
});

// Expose brokk event handler for Java interaction
window.brokk = {
  onEvent: (payload) => {
    console.log('Received event from Java:', payload);
    eventStore.set(payload);

    // ACK after a frame render to ensure UI has updated
    if (payload.epoch) {
      requestAnimationFrame(() => {
        if (window.javaBridge) {
          window.javaBridge.onAck(payload.epoch);
        }
      });
    }
  }
};
console.log('brokk event handler registered.');
