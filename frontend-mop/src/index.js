import { mount } from 'svelte';
import { writable } from 'svelte/store';
import App from './App.svelte';


// Create a writable store for events
const eventStore = writable({ type: '' });

// Instantiate the app using Svelte 5 API
const app = mount(App, {
  target: document.body,
  props: { eventStore }
});

// Save any buffered events
const bufferedEvents = window.brokk._eventBuffer || [];

// Replace the temporary brokk event handler with the real one
window.brokk = {
  onEvent: (payload) => {
    console.log('Received event from Java bridge:', JSON.stringify(payload));
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

// Process any events that were buffered before initialization
if (bufferedEvents.length > 0) {
  bufferedEvents.forEach(event => {
    window.brokk.onEvent(event);
  });
}
