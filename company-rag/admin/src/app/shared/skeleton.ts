import { Component, input } from "@angular/core";
@Component({
  selector: "app-skeleton",
  standalone: true,
  template: `<div
    class="skeleton"
    role="status"
    aria-label="Content is being fetched"
    [style.height]="height()"
    [style.width]="width()"
  ></div>`,
  styles: [
    `
      :host {
        display: block;
      }
      .skeleton {
        border-radius: var(--radius-inner);
        background: linear-gradient(
          90deg,
          var(--color-surface-muted),
          var(--color-border),
          var(--color-surface-muted)
        );
        background-size: 200% 100%;
        animation: shimmer 1.4s infinite;
      }
      @keyframes shimmer {
        to {
          background-position: -200% 0;
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .skeleton {
          animation: none;
        }
      }
    `,
  ],
})
export class Skeleton {
  height = input("20px");
  width = input("100%");
}
