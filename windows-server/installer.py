"""Per-user Windows installer/uninstaller. No administrator or Python installation required."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tkinter as tk
from tkinter import messagebox
import winreg

APP = 'Gemini OCR Translator'
REGISTRY = r'Software\Microsoft\Windows\CurrentVersion\Uninstall\GeminiOCR'
CREATE_NO_WINDOW = 0x08000000


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def desktop_path():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders') as key:
            return Path(os.path.expandvars(winreg.QueryValueEx(key, 'Desktop')[0]))
    except OSError:
        return Path.home() / 'Desktop'


def shortcut(path, target):
    path.parent.mkdir(parents=True, exist_ok=True)
    code = ("$w = New-Object -ComObject WScript.Shell; $s = $w.CreateShortcut(" + ps_quote(path) + ");"
            "$s.TargetPath=" + ps_quote(target) + ";$s.Arguments='';$s.WorkingDirectory=" + ps_quote(target.parent) + ";"
            "$s.IconLocation=" + ps_quote(str(target) + ',0') + ";$s.Save()")
    subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-WindowStyle','Hidden','-Command',code],
                   check=True, creationflags=CREATE_NO_WINDOW)


def payload():
    return Path(getattr(sys, '_MEIPASS', Path(__file__).parent)) / 'payload/GeminiOCR.exe'


def install(target, desktop=True):
    target.mkdir(parents=True, exist_ok=True)
    shutil.copy2(payload(), target / 'GeminiOCR.exe')
    if getattr(sys, 'frozen', False):
        shutil.copy2(sys.executable, target / 'Uninstall.exe')
    start = Path(os.environ['APPDATA']) / 'Microsoft/Windows/Start Menu/Programs/Gemini OCR.lnk'
    shortcut(start, target / 'GeminiOCR.exe')
    if desktop:
        shortcut(desktop_path() / 'Gemini OCR Windows.lnk', target / 'GeminiOCR.exe')
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, REGISTRY) as key:
        for name, value in {'DisplayName':APP,'DisplayVersion':'1.7.1','Publisher':'Gemini OCR local project',
                            'InstallLocation':str(target),'DisplayIcon':str(target / 'GeminiOCR.exe'),
                            'UninstallString':f'"{target / "Uninstall.exe"}" --uninstall'}.items():
            winreg.SetValueEx(key, name, 0, winreg.REG_SZ, value)
        winreg.SetValueEx(key,'NoModify',0,winreg.REG_DWORD,1)
        winreg.SetValueEx(key,'NoRepair',0,winreg.REG_DWORD,1)


def uninstall(target):
    expected = (Path(os.environ['LOCALAPPDATA']) / 'Programs/GeminiOCR').resolve()
    if target.resolve() != expected:
        raise ValueError('Đường dẫn cài đặt không hợp lệ.')
    try:
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, REGISTRY)
    except FileNotFoundError:
        pass
    links = [desktop_path() / 'Gemini OCR Windows.lnk', Path(os.environ['APPDATA']) / 'Microsoft/Windows/Start Menu/Programs/Gemini OCR.lnk']
    for path in links:
        path.unlink(missing_ok=True)
    # A running executable cannot delete itself on Windows. Wait for this exact process, then remove only known program files.
    script = f'Wait-Process -Id {os.getpid()} -ErrorAction SilentlyContinue; '
    for name in ['GeminiOCR.exe','Uninstall.exe']:
        script += 'Remove-Item -LiteralPath ' + ps_quote(target / name) + ' -Force -ErrorAction SilentlyContinue; '
    script += 'try { [System.IO.Directory]::Delete(' + ps_quote(target) + ') } catch {}'
    subprocess.Popen(['powershell.exe','-NoProfile','-NonInteractive','-WindowStyle','Hidden','-Command',script],creationflags=CREATE_NO_WINDOW)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--uninstall', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--extract-to', type=Path)
    parser.add_argument('--install-quiet', action='store_true', help='Install for the current Windows user without opening installer dialogs')
    args = parser.parse_args()
    if args.verify_only:
        assert payload().read_bytes()[:2] == b'MZ', 'Payload is not a Windows executable'
        return
    if args.extract_to:
        args.extract_to.mkdir(parents=True, exist_ok=True)
        shutil.copy2(payload(), args.extract_to / 'GeminiOCR.exe')
        return
    target = Path(os.environ['LOCALAPPDATA']) / 'Programs/GeminiOCR'
    if args.install_quiet:
        install(target)
        return
    window = tk.Tk()
    window.title(('Gỡ cài đặt ' if args.uninstall else 'Cài đặt ') + APP)
    window.geometry('640x580'); window.resizable(False,False); window.configure(bg='#101c22')
    if args.self_test: window.withdraw()
    frame = tk.Frame(window,bg='#101c22',padx=36,pady=30); frame.pack(fill='both',expand=True)
    def label(value,size=11,color='#a1b6bf',pady=8):
        widget=tk.Label(frame,text=value,font=('Segoe UI',size),fg=color,bg='#101c22',wraplength=530,justify='left',anchor='w')
        widget.pack(fill='x',pady=pady); return widget
    label('GEMINI OCR',12,'#9ceacb',0)
    label('Ngôn ngữ không còn\nlà khoảng cách.',25,'#eff6f2',14)
    launch = tk.BooleanVar(value=True); desktop = tk.BooleanVar(value=True)
    if args.uninstall:
        label('Tắt máy chủ trong mục Cài đặt AI trước khi gỡ cài đặt. Chương trình sẽ được xóa; dữ liệu, khóa API đã mã hóa và từ điển của bạn được giữ lại.')
        label(str(target),9)
        def remove():
            try:
                uninstall(target)
                messagebox.showinfo(APP,'Đã gỡ ứng dụng. Dữ liệu được giữ tại %LOCALAPPDATA%\\GeminiOCR\\data.'); window.destroy()
            except Exception as error: messagebox.showerror(APP,str(error))
        command=remove; title='Gỡ cài đặt'
    else:
        label('Máy chủ dịch AI cho Android · phiên bản 1.7.1\nLive OCR, AI Video Translator và Kho dịch thuật. Dịch video dùng Gemini API chính thức; OCR/ASR/render chạy trên máy. Cấu hình và dữ liệu hiện có được giữ nguyên.')
        label('Thư mục: ' + str(target),9)
        for title,var in [('Tạo lối tắt trên màn hình chính',desktop),('Mở Gemini OCR sau khi cài đặt',launch)]:
            tk.Checkbutton(frame,text=title,variable=var,fg='#d4e5de',bg='#101c22',selectcolor='#243d32',activebackground='#101c22',activeforeground='#9ceacb',font=('Segoe UI',10),anchor='w').pack(fill='x',pady=3)
        def setup():
            try:
                button.configure(state='disabled',text='Đang cài đặt…'); window.update_idletasks()
                install(target,desktop.get())
                if launch.get(): subprocess.Popen([str(target / 'GeminiOCR.exe')],creationflags=CREATE_NO_WINDOW)
                messagebox.showinfo(APP,'Cài đặt thành công. Kiểm tra Cài đặt AI và ghép cặp Android. Chương trình giữ nguyên cấu hình đã lưu và không tự chuyển bộ máy dịch.')
                window.destroy()
            except Exception as error:
                button.configure(state='normal',text='Cài đặt cho tôi')
                messagebox.showerror(APP,'Chưa cài đặt được. Nếu app đang chạy, hãy tắt máy chủ rồi thử lại.\n'+str(error))
        command=setup; title='Cài đặt cho tôi  →'
    button=tk.Button(frame,text=title,command=command,font=('Segoe UI',11,'bold'),bg='#9ceacb',fg='#17362a',activebackground='#c1f4da',borderwidth=0,padx=22,pady=11,cursor='hand2')
    button.pack(anchor='w',pady=18)
    if args.self_test:
        window.update_idletasks()
        assert button.winfo_y() + button.winfo_height() <= frame.winfo_height(), 'Install action is clipped'
        assert payload().read_bytes()[:2] == b'MZ'
        window.destroy()
    else:
        window.mainloop()


if __name__ == '__main__':
    main()

