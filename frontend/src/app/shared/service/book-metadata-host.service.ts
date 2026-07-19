import {Injectable} from '@angular/core';
import {Subject} from 'rxjs';

@Injectable({ providedIn: 'root' })
export class BookMetadataHostService {
  private readonly bookSwitchRequest$ = new Subject<number>();

  requestBookSwitch(bookId: number) {
    this.bookSwitchRequest$.next(bookId);
  }

  switchBook(bookId: number): void {
    this.bookSwitchRequest$.next(bookId);
  }

  get bookSwitches$() {
    return this.bookSwitchRequest$.asObservable();
  }
}
