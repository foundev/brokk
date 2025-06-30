import App from './App.svelte';

// Entry point for Brokk frontend
console.log('Brokk frontend initialized');

const app = new App({
  target: document.body
});

// Expose brokk event handler for Java interaction
window.brokk = {
  onEvent: (payload) => {
    console.log('Received event from Java:', payload);
    app.$set({ event: payload });

    // ACK after a frame render to ensure UI has updated
    if (payload.epoch) {
      requestAnimationFrame(() => {
        if (window.javaBridge) {
          window.javaBridge.onAck(payload.epoch);
        } else {
          console.error('javaBridge not available to ACK epoch ' + payload.epoch);
        }
      });
    }
  }
};
console.log('brokk event handler registered.');
