export const ACADEMY_BOOK_FONT = {
  name: 'Academy Book',
  family: 'Academy Book',
  assetPath: 'assets/fonts/AcademyBook-Regular.otf',
  fallback: 'serif',
  sizeAdjust: '135%'
} as const;

export const PT_SERIF_FONT = {
  name: 'PT Serif',
  family: 'PT Serif',
  fallback: 'serif',
  faces: [
    {assetPath: 'assets/fonts/PTSerif-Regular.woff2', weight: 'normal', style: 'normal'},
    {assetPath: 'assets/fonts/PTSerif-Italic.woff2', weight: 'normal', style: 'italic'},
    {assetPath: 'assets/fonts/PTSerif-Bold.woff2', weight: 'bold', style: 'normal'},
    {assetPath: 'assets/fonts/PTSerif-BoldItalic.woff2', weight: 'bold', style: 'italic'},
  ]
} as const;
