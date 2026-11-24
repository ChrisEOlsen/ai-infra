import { Component, input, output } from "@angular/core";
import { MatButtonModule } from "@angular/material/button";
import { MatIconModule } from "@angular/material/icon";
import { MatTooltipModule } from "@angular/material/tooltip";
@Component({
  selector: "app-icon-button",
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `<button
    mat-icon-button
    [attr.aria-label]="label()"
    [matTooltip]="label()"
    [matTooltipDisabled]="!desktop"
    [disabled]="disabled()"
    (click)="pressed.emit()"
  >
    <mat-icon>{{ icon() }}</mat-icon>
  </button>`,
  styles: [
    `
      :host {
        display: inline-flex;
      }
    `,
  ],
})
export class IconButton {
  icon = input.required<string>();
  label = input.required<string>();
  disabled = input(false);
  pressed = output<void>();
  desktop = matchMedia("(hover:hover) and (pointer:fine)").matches;
}
