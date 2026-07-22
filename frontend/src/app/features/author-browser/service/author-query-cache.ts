import {InfiniteData, QueryClient} from '@tanstack/angular-query-experimental';

import {AppPageResponse} from '../../book/model/app-book.model';
import {AuthorSummary} from '../model/author.model';
import {AUTHOR_CATEGORIES_QUERY_KEY, AUTHORS_QUERY_KEY} from './author-query-keys';

export function patchAuthorInCache(queryClient: QueryClient, authorId: number, fields: Partial<AuthorSummary>): void {
  queryClient.setQueriesData<InfiniteData<AppPageResponse<AuthorSummary>>>(
    {queryKey: AUTHORS_QUERY_KEY},
    current => current ? {
      ...current,
      pages: current.pages.map(page => ({
        ...page,
        content: page.content.map(author => author.id === authorId ? {...author, ...fields} : author),
      })),
    } : current
  );
}

export function removeAuthorsFromCache(queryClient: QueryClient, authorIds: Iterable<number>): void {
  const removedIds = new Set(authorIds);
  if (removedIds.size === 0) return;

  queryClient.setQueriesData<InfiniteData<AppPageResponse<AuthorSummary>>>(
    {queryKey: AUTHORS_QUERY_KEY},
    current => {
      if (!current) return current;

      const removedCount = current.pages
        .flatMap(page => page.content)
        .filter(author => removedIds.has(author.id))
        .length;
      if (removedCount === 0) return current;

      return {
        ...current,
        pages: current.pages.map(page => {
          const content = page.content.filter(author => !removedIds.has(author.id));
          const totalElements = Math.max(0, page.totalElements - removedCount);
          const totalPages = page.size > 0 ? Math.ceil(totalElements / page.size) : 0;
          return {
            ...page,
            content,
            totalElements,
            totalPages,
            hasNext: page.page < totalPages - 1,
          };
        }),
      };
    }
  );
}

export function invalidateAuthorsQuery(queryClient: QueryClient): void {
  queryClient.invalidateQueries({queryKey: AUTHORS_QUERY_KEY});
  queryClient.invalidateQueries({queryKey: AUTHOR_CATEGORIES_QUERY_KEY, exact: true});
}
