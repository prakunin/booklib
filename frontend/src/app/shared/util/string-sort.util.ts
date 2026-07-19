export function compareStrings(left: string, right: string): number {
  return left.localeCompare(right);
}

export function sortStrings<T extends string>(values: readonly T[]): T[] {
  return [...values].sort(compareStrings);
}
