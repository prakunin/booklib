import {computed, inject, Injectable, signal} from '@angular/core';
import {LibraryService} from '../../../../book/service/library.service';
import {TranslocoService} from '@jsverse/transloco';

export interface LibraryOption {
  id: number | null;
  name: string;
}

@Injectable({
  providedIn: 'root'
})
export class LibraryFilterService {
  private readonly selectedLibraryId = signal<number | null>(null);

  readonly selectedLibrary = computed(() => {
    const selectedLibraryId = this.selectedLibraryId();
    return this.libraryOptions().some(option => option.id === selectedLibraryId)
      ? selectedLibraryId
      : null;
  });

  setSelectedLibrary(libraryId: number | null): void {
    this.selectedLibraryId.set(libraryId);
  }

  private readonly libraryService = inject(LibraryService);
  private readonly t = inject(TranslocoService);
  readonly libraryOptions = computed(() => {
    const libraries = this.libraryService.libraries();

    const options: LibraryOption[] = [
      {id: null, name: this.t.translate('statsLibrary.libraryFilter.allLibraries')},
      ...libraries
        .filter((library): library is typeof library & {id: number} => library.id != null)
        .map(library => ({id: library.id, name: library.name}))
    ];

    return options.sort((a, b) => {
      if (a.id === null) return -1;
      if (b.id === null) return 1;
      return a.name.localeCompare(b.name);
    });
  });
}
