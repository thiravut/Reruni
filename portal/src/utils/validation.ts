// Client-side validation per tech-spec.md §3.

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function validateEmail(email: string): string | null {
  if (!email) return 'กรุณากรอกอีเมล';
  if (email.length > 255) return 'อีเมลยาวเกินไป';
  if (!EMAIL_RE.test(email)) return 'รูปแบบอีเมลไม่ถูกต้อง';
  return null;
}

export function validatePassword(password: string): string | null {
  if (!password) return 'กรุณากรอกรหัสผ่าน';
  if (password.length < 8) return 'รหัสผ่านต้องมีอย่างน้อย 8 ตัวอักษร';
  if (!/[A-Za-z]/.test(password)) return 'รหัสผ่านต้องมีตัวอักษรอย่างน้อย 1 ตัว';
  if (!/[0-9]/.test(password)) return 'รหัสผ่านต้องมีตัวเลขอย่างน้อย 1 ตัว';
  return null;
}

export function validateDeviceName(name: string): string | null {
  if (!name) return 'กรุณาตั้งชื่ออุปกรณ์';
  if (name.length > 50) return 'ชื่ออุปกรณ์ยาวไม่เกิน 50 ตัวอักษร';
  return null;
}

export function validateLiveTitle(title: string): string | null {
  if (!title) return 'กรุณากรอกชื่อ live';
  if (title.length > 100) return 'ชื่อ live ยาวไม่เกิน 100 ตัวอักษร';
  return null;
}

export function validateLiveCaption(caption: string): string | null {
  if (caption.length > 500) return 'คำบรรยายยาวไม่เกิน 500 ตัวอักษร';
  return null;
}

export function validateHashtags(tags: string[]): string | null {
  if (tags.length > 10) return 'แฮชแท็กไม่เกิน 10 อัน';
  for (const tag of tags) {
    if (tag.length > 30) return 'แต่ละแฮชแท็กยาวไม่เกิน 30 ตัวอักษร';
    if (/\s/.test(tag)) return 'แฮชแท็กต้องไม่มีช่องว่าง';
  }
  return null;
}

export function validateBannerText(text: string): string | null {
  if (!text) return 'กรุณากรอกข้อความ';
  if (text.length > 80) return 'ข้อความยาวไม่เกิน 80 ตัวอักษร';
  return null;
}

export function validateVideoFile(file: File): string | null {
  const okTypes = ['video/mp4', 'video/quicktime'];
  const okExts = ['.mp4', '.mov'];
  const nameLower = file.name.toLowerCase();
  const extOk = okExts.some((ext) => nameLower.endsWith(ext));
  if (!okTypes.includes(file.type) && !extOk) {
    return 'รองรับเฉพาะไฟล์ mp4 หรือ mov';
  }
  if (file.size > 500 * 1024 * 1024) {
    return 'ขนาดไฟล์ต้องไม่เกิน 500 MB';
  }
  return null;
}
