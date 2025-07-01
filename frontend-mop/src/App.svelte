<script>
  import SvelteMarkdown from '@humanspeak/svelte-markdown';

  export let event = { type: '' };

  let fullSource = '';
  let tokens = [];
  let spinnerMessage = '';
  let isDarkTheme = false;
  let nextId = 0;

  function newId() {
    return `t${nextId++}`;
  }

  function addOrKeepId(token) {
    if (!token.id) {
      token.id = newId();
    }
    if (token.tokens) {
      token.tokens.forEach(addOrKeepId);
    }
  }

  function mergeTokens(oldTokens, newTokens) {
    const byRaw = new Map(oldTokens.map(t => [`${t.raw}-${t.id}`, t]));
    for (const nt of newTokens) {
      const key = `${nt.raw}-${nt.id || ''}`;
      if (byRaw.has(key)) {
        nt.id = byRaw.get(key).id;
      } else if (!nt.id) {
        nt.id = newId();
      }
      if (nt.tokens) {
        nt.tokens = mergeTokens(oldTokens.flatMap(t => t.tokens || []), nt.tokens);
      }
    }
    return newTokens;
  }

  $: {
    switch (event.type) {
      case 'chunk':
        if (event.content) {
          fullSource += event.content;
          const newTokens = marked.lexer(fullSource);
          marked.walkTokens(newTokens, addOrKeepId);
          tokens = mergeTokens(tokens, newTokens);
        }
        break;
      case 'theme':
        isDarkTheme = event.dark;
        break;
      case 'clear':
        fullSource = '';
        tokens = [];
        break;
      case 'spinner':
        spinnerMessage = event.message;
        break;
    }
  }
</script>

<style>
  body.theme-dark {
    background-color: #2b2b2b;
    color: #bbb;
  }
  .content {
    white-space: pre-wrap;
    word-break: break-all;
  }
  #spinner {
    padding: 0.5em;
    color: #888;
    display: none;
  }
  body.theme-dark #spinner {
    color: #888;
  }
</style>

<h1>Brokk MOP with Svelte</h1>
<p>Events from Java will be logged to the console and rendered as Markdown below.</p>
<div class="content" class:theme-dark={isDarkTheme}>
  {#each tokens as token (token.id)}
    <SvelteMarkdown source={[token]} />
  {/each}
</div>
<div id="spinner" style:display={spinnerMessage ? 'block' : 'none'}>{spinnerMessage}</div>
