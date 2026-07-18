import {Injectable} from '@angular/core';

export type EmbedPdfMessage =
  | { type: 'ready' }
  | { type: 'documentOpened'; pageCount?: number }
  | { type: 'documentError'; error: string }
  | { type: 'saved'; buffer?: ArrayBuffer }
  | { type: 'saveError'; error: string }
  | { type: 'pageChange'; pageNumber: number; totalPages: number };

export interface PdfEmbedCommand {
  type: string;
  [key: string]: unknown;
}

@Injectable({
  providedIn: 'root'
})
export class PdfEmbedBridgeService {
  post(iframe: HTMLIFrameElement | null | undefined, message: PdfEmbedCommand, transfer: Transferable[] = []): boolean {
    const contentWindow = iframe?.contentWindow;
    if (!contentWindow) {
      return false;
    }

    if (transfer.length) {
      contentWindow.postMessage(message, location.origin, transfer);
    } else {
      contentWindow.postMessage(message, location.origin);
    }
    return true;
  }

  createMessageHandler(iframe: HTMLIFrameElement, onMessage: (message: EmbedPdfMessage) => void): (event: MessageEvent) => void {
    return (event: MessageEvent) => {
      if (event.origin !== location.origin) return;
      if (event.source !== iframe.contentWindow) return;
      onMessage(event.data as EmbedPdfMessage);
    };
  }
}
