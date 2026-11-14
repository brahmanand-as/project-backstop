import {Component} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

interface Flag {rule: string; ratio: number; lastSeenAt: string}
interface Audit {action: string; actor: string; reason: string; fromStatus: string; toStatus: string; at: string}
interface Merchant {
  merchantId: string; version: number; status: string; sales: number; refunds: number; chargebacks: number;
  refundRatio: number; chargebackRatio: number; refundLimit: number; chargebackLimit: number;
  openFlags: Flag[]; audit: Audit[];
}
interface Page {items: Merchant[]; page: number; size: number; total: number}

@Component({selector:'bank-root',standalone:true,imports:[CommonModule,FormsModule],templateUrl:'./portal.html'})
export class App {
  username = 'analyst1';
  password = '';
  private auth = '';
  role = '';
  signedIn = false;
  busy = false;
  status = '';
  page = 0;
  list?: Page;
  detail?: Merchant;
  reason = '';
  error = '';
  notice = '';

  async request<T>(path: string, body?: unknown): Promise<T> {
    const response = await fetch('/api/merchants' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {Authorization: this.auth, 'Content-Type':'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      throw Object.assign(new Error(result.message || 'Request failed'),{status: response.status});
    }
    return response.json() as Promise<T>;
  }

  async run(operation: () => Promise<void>) {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    this.notice = '';
    try { await operation(); }
    catch (error) { this.error = error instanceof Error ? error.message : 'Request failed'; }
    finally { this.busy = false; }
  }

  async refresh() {
    const query = new URLSearchParams({flagged:'true',page:String(this.page)});
    if (this.status) query.set('status',this.status);
    this.list = await this.request<Page>('?' + query);
  }

  login() {
    return this.run(async () => {
      const response = await fetch('/api/login', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({username:this.username,password:this.password})
      });
      if (!response.ok) throw new Error('Invalid credentials or API unavailable');
      const result = await response.json() as {token:string;role:string};
      this.auth = 'Bearer ' + result.token;
      this.role = result.role;
      await this.refresh();
      this.password = '';
      this.signedIn = true;
    });
  }

  logout() {
    this.auth = '';
    this.role = '';
    this.signedIn = false;
    this.detail = undefined;
    this.list = undefined;
    this.page = 0;
    this.status = '';
    this.reason = '';
    this.notice = '';
    this.error = '';
  }

  browse(page: number) {
    return this.run(async () => { this.page = page; await this.refresh(); });
  }

  select(id: string) {
    return this.run(async () => {
      this.detail = await this.request<Merchant>('/' + encodeURIComponent(id));
      this.reason = '';
    });
  }

  canDecide(merchant: Merchant) {
    return merchant.status === "ACTIVE" || this.role === "SUPERVISOR";
  }

  decide(merchant: Merchant) {
    return this.run(async () => {
      const uri = '/' + encodeURIComponent(merchant.merchantId);
      const action = merchant.status === 'ACTIVE' ? 'hold' : 'release';
      try {
        await this.request(uri + '/' + action,{version:merchant.version,reason:this.reason.trim()});
      } catch (error) {
        if ((error as {status?:number}).status !== 409) throw error;
        this.detail = await this.request<Merchant>(uri);
        this.notice = 'This merchant was changed by someone else. Reloaded the latest state.';
        this.reason = '';
        await this.refresh();
        return;
      }
      this.detail = await this.request<Merchant>(uri);
      this.reason = '';
      await this.refresh();
    });
  }
}

