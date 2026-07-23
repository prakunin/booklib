import {ChangeDetectionStrategy, Component, input, output} from '@angular/core';
import {TranslocoDirective} from '@jsverse/transloco';

@Component({
  selector: 'app-footnote-popup',
  standalone: true,
  imports: [TranslocoDirective],
  templateUrl: './footnote-popup.component.html',
  styleUrls: ['./footnote-popup.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class FootnotePopupComponent {
  readonly visible = input(false);
  readonly position = input<{ x: number; y: number }>({x: 0, y: 0});
  readonly showBelow = input(false);
  readonly html = input('');
  readonly background = input('');
  readonly foreground = input('');
  readonly dark = input(false);

  readonly dismissed = output<void>();
  readonly openFullRequested = output<void>();
}
