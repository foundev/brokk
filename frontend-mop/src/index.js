// Entry point for Brokk frontend
console.log('Brokk frontend initialized');

// Add your application logic here
const log = document.getElementById('log');
const spinner = document.getElementById('spinner');

window.brokk = {
  onEvent: (payload) => {
    console.log('Received event from Java:', payload);

    switch (payload.type) {
      case 'chunk':
        log.textContent += JSON.stringify(payload, null, 2) + '\n';
        break;
      case 'theme':
        document.body.classList.toggle('theme-dark', payload.dark);
        break;
      case 'clear':
        log.textContent = '';
        break;
      case 'spinner':
        spinner.textContent = payload.message;
        spinner.style.display = payload.message ? 'block' : 'none';
        break;
    }

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
