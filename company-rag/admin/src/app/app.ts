import { Component, inject, signal, OnDestroy, OnInit } from "@angular/core";
import { DatePipe } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { MatIconModule } from "@angular/material/icon";
import { MatFormFieldModule } from "@angular/material/form-field";
import { MatSelectModule } from "@angular/material/select";
import { MatProgressBarModule } from "@angular/material/progress-bar";
import { MatDialog } from "@angular/material/dialog";
import { MatSnackBar } from "@angular/material/snack-bar";
import { forkJoin, firstValueFrom } from "rxjs";
import { Api, Collection, Document, Source } from "./api";
import { IconButton } from "./shared/icon-button";
import { Skeleton } from "./shared/skeleton";
import { Editor, Confirm } from "./shared/editor";
@Component({
  selector: "app-root",
  standalone: true,
  imports: [
    FormsModule,
    DatePipe,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressBarModule,
    IconButton,
    Skeleton,
  ],
  templateUrl: "./app.html",
})
export class App implements OnInit, OnDestroy {
  api = inject(Api);
  dialog = inject(MatDialog);
  snack = inject(MatSnackBar);
  page = signal<"Documents" | "Collections" | "Sources">("Documents");
  collections = signal<Collection[]>([]);
  documents = signal<Document[]>([]);
  sources = signal<Source[]>([]);
  loading = signal(true);
  uploading = signal(false);
  busy = signal(false);
  error = signal("");
  authorized = signal(false);
  collection = "";
  dragging = false;
  timer?: ReturnType<typeof setInterval>;
  async ngOnInit() {
    try {
      const me = await firstValueFrom(this.api.get<{ roles: string[] }>("me"));
      if (!me.roles.includes("company-admin")) {
        this.error.set("Your account does not have administrator access.");
        this.loading.set(false);
        return;
      }
      this.authorized.set(true);
      await this.load();
      this.timer = setInterval(() => {
        if (!this.busy() && !this.uploading()) void this.load(false);
      }, 5000);
    } catch (e) {
      this.fail(e);
    }
  }
  ngOnDestroy() {
    clearInterval(this.timer);
  }
  async load(initial = true) {
    if (initial) this.loading.set(true);
    try {
      const [collections, documents, sources] = await firstValueFrom(
        forkJoin([
          this.api.get<Collection[]>("admin/collections"),
          this.api.get<Document[]>("admin/documents"),
          this.api.get<Source[]>("admin/sources"),
        ]),
      );
      this.collections.set(collections);
      this.documents.set(documents);
      this.sources.set(sources);
      if (!this.collection && collections.length)
        this.collection = collections[0].id;
      this.error.set("");
    } catch (e) {
      this.fail(e);
    } finally {
      this.loading.set(false);
    }
  }
  fail(e: any) {
    this.error.set(
      e?.error?.message ||
        "The operation could not be completed. Please try again.",
    );
    this.loading.set(false);
  }
  async act(work: () => Promise<unknown>, success: string) {
    this.busy.set(true);
    try {
      await work();
      this.snack.open(success, undefined, { duration: 3000 });
      await this.load(false);
    } catch (e) {
      this.fail(e);
    } finally {
      this.busy.set(false);
    }
  }
  async editCollection(c?: Collection) {
    const values = await firstValueFrom(
      this.dialog
        .open(Editor, {
          data: {
            title: c ? "Edit collection" : "New collection",
            fields: [
              { key: "name", label: "Collection name", max: 100 },
              { key: "roles", label: "Allowed roles, separated by commas" },
            ],
            values: {
              name: c?.name || "",
              roles: c?.roles.join(", ") || "employee",
            },
          },
        })
        .afterClosed(),
    );
    if (!values) return;
    const body = {
      name: values.name,
      roles: values.roles
        .split(",")
        .map((r: string) => r.trim())
        .filter(Boolean),
    };
    await this.act(
      () =>
        firstValueFrom(
          c
            ? this.api.put("admin/collections/" + c.id, body)
            : this.api.post("admin/collections", body),
        ),
      "Collection saved",
    );
  }
  async addSource() {
    if (!this.collections().length) return;
    const v = await firstValueFrom(
      this.dialog
        .open(Editor, {
          data: {
            title: "Connect S3 source",
            action: "Connect",
            fields: [
              {
                key: "collection_id",
                label: "Collection",
                options: this.collections(),
              },
              { key: "bucket", label: "Bucket name" },
              {
                key: "prefix",
                label: "Folder prefix ending in /",
                optional: true,
              },
            ],
            values: { collection_id: this.collection, prefix: "" },
          },
        })
        .afterClosed(),
    );
    if (v)
      await this.act(
        () => firstValueFrom(this.api.post("admin/sources", v)),
        "Source connected",
      );
  }
  async upload(files: FileList | File[] | null) {
    if (!files || !this.collection || this.uploading()) return;
    this.uploading.set(true);
    try {
      for (const file of Array.from(files))
        await firstValueFrom(this.api.upload(this.collection, file));
      this.snack.open("Documents queued", undefined, { duration: 3000 });
      await this.load(false);
    } catch (e) {
      this.fail(e);
    } finally {
      this.uploading.set(false);
    }
  }
  drop(event: DragEvent) {
    event.preventDefault();
    this.dragging = false;
    if (this.authorized()) void this.upload(event.dataTransfer?.files || null);
  }
  async remove(kind: "documents" | "sources", id: string, name: string) {
    const yes = await firstValueFrom(
      this.dialog
        .open(Confirm, {
          data: {
            title:
              kind === "sources" ? "Disconnect source?" : "Delete document?",
            message:
              kind === "sources"
                ? "Indexed documents from this source will be removed. Original S3 objects remain unchanged."
                : `Remove “${name}” from employee search?`,
          },
        })
        .afterClosed(),
    );
    if (yes)
      await this.act(
        () => firstValueFrom(this.api.delete("admin/" + kind + "/" + id)),
        "Removed",
      );
  }
  async retry(id: string) {
    await this.act(
      () =>
        firstValueFrom(this.api.post("admin/documents/" + id + "/retry", {})),
      "Retry queued",
    );
  }
  async sync(id: string) {
    await this.act(
      () => firstValueFrom(this.api.post("admin/sources/" + id + "/sync", {})),
      "Synchronization queued",
    );
  }
  logout() {
    const token = document.cookie
      .split("; ")
      .find((x) => x.startsWith("XSRF-TOKEN="))
      ?.split("=")
      .slice(1)
      .join("=");
    const form = document.createElement("form");
    form.method = "POST";
    form.action = "/logout";
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = "_csrf";
    input.value = decodeURIComponent(token || "");
    form.append(input);
    document.body.append(form);
    form.submit();
  }
}
