import {Observable} from 'rxjs';

/**
 * Consumes a server-sent-event stream from an endpoint that requires POST and a bearer token.
 *
 * `EventSource` cannot do either, so the stream is read off `fetch` by hand. Unsubscribing aborts
 * the request, which matters here: these endpoints stay open for as long as the server keeps
 * working, so a closed dialog must not leave the backend talking to nobody.
 */
export function streamServerSentEvents<T>(
  url: string,
  token: string,
  init?: {method?: string; body?: unknown}
): Observable<T> {
  return new Observable<T>((subscriber) => {
    const abortController = new AbortController();

    fetch(url, {
      method: init?.method ?? 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: init?.body === undefined ? undefined : JSON.stringify(init.body),
      signal: abortController.signal,
    })
      .then(async (response) => {
        if (!response.ok) {
          subscriber.error(new Error(`HTTP error! status: ${response.status}`));
          return;
        }

        const reader = response.body?.getReader();
        if (!reader) {
          subscriber.error(new Error('Response body is null'));
          return;
        }

        const decoder = new TextDecoder();
        let buffer = '';
        const emitLine = (line: string) => {
          if (!line.startsWith('data:')) {
            return;
          }
          const data = line.slice(5).trim();
          if (!data) {
            return;
          }
          try {
            subscriber.next(JSON.parse(data) as T);
          } catch (e) {
            console.error('Error parsing SSE data:', e);
          }
        };

        try {
          while (true) {
            const {done, value} = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, {stream: true});
            const lines = buffer.split('\n');
            // The final chunk of a split is whatever came after the last newline: it may be half a
            // line, so it is held back until the next read completes it.
            buffer = lines.pop() || '';

            for (const line of lines) {
              emitLine(line);
            }
          }
          if (buffer) {
            emitLine(buffer);
          }
          subscriber.complete();
        } catch (error) {
          if (!(error instanceof Error && error.name === 'AbortError')) {
            subscriber.error(error);
          }
        } finally {
          reader.releaseLock();
        }
      })
      .catch((error) => {
        if (error instanceof Error && error.name === 'AbortError') {
          return;
        }
        subscriber.error(error);
      });

    return () => abortController.abort();
  });
}
