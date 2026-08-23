import React, { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import { SmppClient } from '../types';

interface SmppClientFormFieldsProps {
    formData: Partial<SmppClient>;
    setFormData: (data: Partial<SmppClient>) => void;
    layout?: 'horizontal-td' | 'vertical-div';
    showUsernameSelect?: boolean;
    allUsernames?: any[];
    accounts?: any[];
    isCreating?: boolean;
    originalPasswordPlaceholder?: string;
}

export function SmppClientFormFields({ 
    formData, 
    setFormData, 
    layout = 'vertical-div',
    showUsernameSelect = true,
    allUsernames = [],
    accounts = [],
    isCreating = false,
    originalPasswordPlaceholder = "secret"
}: SmppClientFormFieldsProps) {
    const [showPassword, setShowPassword] = useState(false);

    const inputClass = layout === 'horizontal-td' 
        ? "w-full bg-white dark:bg-[#12121f] border border-brand-500/50 rounded px-2 py-1 text-slate-900 dark:text-white text-sm" 
        : "w-full bg-white dark:bg-[#12121f] border border-slate-200 dark:border-white/10 rounded px-3 py-2 text-slate-900 dark:text-white text-sm";
    
    const wrapperClass = layout === 'horizontal-td' ? "" : "flex flex-col gap-1";
    const labelClass = "block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1";

    const renderField = (label: string, element: React.ReactNode, isTd: boolean) => {
        if (isTd) return <td className="px-5 py-3">{element}</td>;
        return (
            <div className={wrapperClass}>
                <label className={labelClass}>{label}</label>
                {element}
            </div>
        );
    };

    const isTd = layout === 'horizontal-td';

    return (
        <>
            {renderField('Client Name', (
                <input autoFocus={isTd} className={inputClass}
                    value={formData.name || ''} 
                    onChange={e => setFormData({ ...formData, name: e.target.value })} 
                    placeholder="Client Name" />
            ), isTd)}

            {showUsernameSelect && isCreating && renderField('Link to Account', (
                <select className={inputClass}
                    value={(formData as any).accountId || ''} 
                    onChange={e => setFormData({ ...formData, accountId: parseInt(e.target.value) || undefined } as any)}
                    disabled={accounts.length === 0}>
                    <option value="" disabled>
                        {accounts.length > 0 ? '-- Select Account --' : '-- No Accounts Available --'}
                    </option>
                    {accounts.map((a: any) => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
            ), isTd)}

            {showUsernameSelect && !isCreating && renderField('Linked Username', (
                <select className={inputClass} disabled
                    value={formData.usernameId || ''} 
                    onChange={e => setFormData({ ...formData, usernameId: parseInt(e.target.value) || undefined })}>
                    <option value="" disabled>-- Required Username --</option>
                    {allUsernames.map((u: any) => <option key={u.id} value={u.id}>{u.accountName} - {u.username}</option>)}
                </select>
            ), isTd)}

            {renderField('System ID', (
                <input className={inputClass}
                    value={formData.systemId || ''} 
                    onChange={e => setFormData({ ...formData, systemId: e.target.value })} 
                    placeholder="username" />
            ), isTd)}

            {renderField('Password', (
                <div className="relative flex items-center">
                    <input 
                        type={showPassword ? "text" : "password"}
                        className={`${inputClass} ${isTd ? 'pr-8' : ''}`} 
                        value={formData.password || ''} 
                        onChange={e => setFormData({ ...formData, password: e.target.value })} 
                        placeholder={originalPasswordPlaceholder} 
                    />
                    <button 
                        type="button"
                        onClick={() => setShowPassword(!showPassword)}
                        className={`absolute text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-white transition ${isTd ? 'right-2' : 'right-3'}`}
                    >
                        {showPassword ? <EyeOff size={14} /> : <Eye size={14} />}
                    </button>
                </div>
            ), isTd)}

            {renderField('Active Status', (
                <label className={isTd ? "" : "flex items-center gap-2 mt-2 cursor-pointer"}>
                    <input type="checkbox" className="form-checkbox text-brand-500 rounded bg-white dark:bg-[#12121f] border-slate-300 dark:border-white/20"
                        checked={formData.active !== false} 
                        onChange={e => setFormData({ ...formData, active: e.target.checked })} /> 
                    {isTd ? ' Active' : <span className="text-sm text-slate-300">Active Connection</span>}
                </label>
            ), isTd)}

            {renderField('Priority', (
                <select className={inputClass} 
                    value={formData.priority || 2} 
                    onChange={e => setFormData({ ...formData, priority: parseInt(e.target.value) })}>
                    <option value={1}>1 (OTP)</option>
                    <option value={2}>2 (Marketing)</option>
                </select>
            ), isTd)}
        </>
    );
}
