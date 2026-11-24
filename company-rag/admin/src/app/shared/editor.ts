import { Component, inject } from "@angular/core";
import { FormsModule } from "@angular/forms";
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
} from "@angular/material/dialog";
import { MatFormFieldModule } from "@angular/material/form-field";
import { MatInputModule } from "@angular/material/input";
import { MatSelectModule } from "@angular/material/select";
import { MatButtonModule } from "@angular/material/button";
import { IconButton } from "./icon-button";
@Component({
  standalone: true,
  imports: [
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    IconButton,
  ],
  template: ` <div class="dialog-heading">
      <h2 mat-dialog-title>{{ data.title }}</h2>
      <app-icon-button
        icon="close"
        label="Close dialog"
        (pressed)="ref.close()"
      />
    </div>
    <mat-dialog-content
      ><form id="editor" (ngSubmit)="save()">
        @for (field of data.fields; track field.key) {
          <mat-form-field appearance="outline"
            ><mat-label>{{ field.label }}</mat-label>
            @if (field.options) {
              <mat-select
                [(ngModel)]="values[field.key]"
                [name]="field.key"
                required
              >
                @for (option of field.options; track option.id) {
                  <mat-option [value]="option.id">{{ option.name }}</mat-option>
                }
              </mat-select>
            } @else {
              <input
                matInput
                [(ngModel)]="values[field.key]"
                [name]="field.key"
                [required]="!field.optional"
                [maxlength]="field.max || 256"
              />
            }
          </mat-form-field>
        }</form></mat-dialog-content
    ><mat-dialog-actions
      ><button mat-flat-button form="editor" type="submit">
        {{ data.action || "Save" }}
      </button></mat-dialog-actions
    >`,
  styles: [
    `
      form {
        display: grid;
        gap: 8px;
        min-width: min(360px, 70vw);
      }
      .dialog-heading {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding-right: 12px;
      }
      h2 {
        margin: 0;
      }
    `,
  ],
})
export class Editor {
  data = inject(MAT_DIALOG_DATA);
  ref = inject(MatDialogRef<Editor>);
  values: Record<string, string> = { ...this.data.values };
  save() {
    if (
      this.data.fields.some(
        (f: any) => !f.optional && !this.values[f.key]?.trim(),
      )
    )
      return;
    this.ref.close(this.values);
  }
}
@Component({
  standalone: true,
  imports: [MatDialogModule, MatButtonModule],
  template: `<h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>{{ data.message }}</mat-dialog-content
    ><mat-dialog-actions
      ><button mat-button (click)="ref.close(false)">Cancel</button
      ><button mat-flat-button (click)="ref.close(true)">
        {{ data.action || "Delete" }}
      </button></mat-dialog-actions
    >`,
})
export class Confirm {
  data = inject(MAT_DIALOG_DATA);
  ref = inject(MatDialogRef<Confirm>);
}
