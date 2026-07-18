import {describe, expect, it, vi} from 'vitest';

import {PdfEmbedBridgeService} from './pdf-embed-bridge.service';

describe('PdfEmbedBridgeService', () => {
  const service = new PdfEmbedBridgeService();

  it('posts commands to an iframe content window', () => {
    const postMessage = vi.fn();
    const iframe = {contentWindow: {postMessage}} as unknown as HTMLIFrameElement;

    expect(service.post(iframe, {type: 'nextPage'})).toBe(true);

    expect(postMessage).toHaveBeenCalledWith({type: 'nextPage'}, location.origin);
  });

  it('posts transferable commands when provided', () => {
    const postMessage = vi.fn();
    const iframe = {contentWindow: {postMessage}} as unknown as HTMLIFrameElement;
    const buffer = new ArrayBuffer(1);

    expect(service.post(iframe, {type: 'load', buffer}, [buffer])).toBe(true);

    expect(postMessage).toHaveBeenCalledWith({type: 'load', buffer}, location.origin, [buffer]);
  });

  it('returns false when the iframe is not ready', () => {
    expect(service.post(null, {type: 'save'})).toBe(false);
    expect(service.post({contentWindow: null} as unknown as HTMLIFrameElement, {type: 'save'})).toBe(false);
  });

  it('filters window messages by origin and source', () => {
    const contentWindow = {} as Window;
    const iframe = {contentWindow} as HTMLIFrameElement;
    const onMessage = vi.fn();
    const handler = service.createMessageHandler(iframe, onMessage);

    handler(new MessageEvent('message', {origin: 'https://other.example', source: contentWindow, data: {type: 'ready'}}));
    handler(new MessageEvent('message', {origin: location.origin, source: {} as Window, data: {type: 'ready'}}));
    handler(new MessageEvent('message', {origin: location.origin, source: contentWindow, data: {type: 'ready'}}));

    expect(onMessage).toHaveBeenCalledOnce();
    expect(onMessage).toHaveBeenCalledWith({type: 'ready'});
  });
});
