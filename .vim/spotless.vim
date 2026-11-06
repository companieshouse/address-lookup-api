let s:spotless_root = fnamemodify(expand('<sfile>:p'), ':h:h')

function! s:Spotless() abort
  if expand('%:e') !=# 'java' || empty(expand('%:p'))
    echoerr 'Spotless requires a named Java file.'
    return
  endif
  update
  let l:view = winsaveview()
  let l:output = system('env ' . shellescape('FILE=' . expand('%:p'))
        \ . ' bash ' . shellescape(s:spotless_root . '/scripts/spotless.sh')
        \ . ' tidy')
  if v:shell_error
    echoerr 'Spotless failed: ' . l:output
    return
  endif
  edit
  call winrestview(l:view)
endfunction

command! Spotless call <SID>Spotless()
