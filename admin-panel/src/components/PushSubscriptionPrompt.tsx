import React, { useEffect, useState } from 'react';
import { getPushPublicKey, syncPushSubscription } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { Bell } from 'lucide-react';

function urlBase64ToUint8Array(base64String: string) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding)
        .replace(/\-/g, '+')
        .replace(/_/g, '/');
    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; ++i) {
        outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
}

export default function PushSubscriptionPrompt() {
    const { isAuthenticated } = useAuth();
    const [showPrompt, setShowPrompt] = useState(false);

    useEffect(() => {
        if (!isAuthenticated) return;

        // Check if push is supported 
        if ('serviceWorker' in navigator && 'PushManager' in window) {
            navigator.serviceWorker.register('/service-worker.js').then(registration => {
                registration.pushManager.getSubscription().then(subscription => {
                    const permission = window.Notification.permission;
                    
                    if (permission === 'default' && !subscription) {
                        setShowPrompt(true);
                    } else if (permission === 'granted' && subscription) {
                        syncSubscriptionWithBackend(subscription);
                    } else if (permission === 'granted' && !subscription) {
                        setShowPrompt(true);
                    }
                });
            }).catch(e => console.error('Service Worker registration failed', e));
        }
    }, [isAuthenticated]);

    const syncSubscriptionWithBackend = async (subscription: PushSubscription) => {
        try {
            const subData = subscription.toJSON();
            await syncPushSubscription(subData);
        } catch (error) {
            console.error('Failed to sync push subscription', error);
        }
    };

    const handleSubscribe = async () => {
        setShowPrompt(false);
        try {
            const permission = await window.Notification.requestPermission();
            if (permission !== 'granted') {
                return;
            }

            const registration = await navigator.serviceWorker.ready;
            const publicData = await getPushPublicKey();
            
            if (!publicData?.publicKey) {
                 console.error("VAPID missing public key payload");
                 return;
            }
            
            const applicationServerKey = urlBase64ToUint8Array(publicData.publicKey);

            const subscription = await registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey
            });

            await syncSubscriptionWithBackend(subscription);
            
            // Optionally dispatch a success toast here
            console.log("Successfully subscribed!");
        } catch (error) {
            console.error('Failed to subscribe to push notifications', error);
        }
    };

    const handleDismiss = () => {
        setShowPrompt(false);
        localStorage.setItem('pushPromptDismissed', Date.now().toString());
    };

    if (!showPrompt) return null;

    const dismissedTime = localStorage.getItem('pushPromptDismissed');
    if (dismissedTime && (Date.now() - parseInt(dismissedTime) < 24 * 60 * 60 * 1000)) {
        return null; 
    }

    return (
        <div className="fixed bottom-4 right-4 bg-slate-800 border border-slate-700 p-4 rounded-lg shadow-xl z-[9999] animate-in slide-in-from-bottom flex flex-col gap-3 w-80">
            <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded bg-brand-600/20 text-brand-400 flex flex-shrink-0 items-center justify-center">
                    <Bell size={20} />
                </div>
                <div>
                    <h4 className="text-white font-medium text-sm">Enable Notifications</h4>
                    <p className="text-slate-400 text-xs mt-0.5 leading-relaxed">
                        Receive instant alerts about system issues, SMPP drops, and queue pileups.
                    </p>
                </div>
            </div>
            <div className="flex items-center justify-end gap-2 mt-1">
                <button 
                    onClick={handleDismiss}
                    className="px-3 py-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition bg-slate-800/50 hover:bg-slate-700/50 rounded"
                >
                    Not Now
                </button>
                <button 
                    onClick={handleSubscribe}
                    className="px-3 py-1.5 text-xs font-medium text-white bg-brand-600 hover:bg-brand-500 transition rounded shadow-sm flex items-center gap-1.5 shadow-brand-900/20"
                >
                    Enable
                </button>
            </div>
        </div>
    );
}
