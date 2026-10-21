import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './style.css'

const vehicle = new URLSearchParams(window.location.search).get('vehicle') || undefined
createRoot(document.getElementById('root')!).render(<StrictMode><App vehicle={vehicle} /></StrictMode>)
