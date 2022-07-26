import React, { useEffect } from 'react';

export default function EditArea({ id }) {
  useEffect(() => {
    const script = document.createElement('script');
    script.src('../../editarea/edit_area/edit_area_full.js');
    document.body.appendChild(script);

    return () => {
      document.body.removeChild(script);
    };
  });

  editAreaLoader.init({
    id: 'v' + id, // textarea id
    syntax: 'yql', // syntax to be uses for highgliting
    start_highlight: true, // to display with highlight mode on start-up
    autocompletion: true,
    plugins: 'autocompletion',
    gecko_spellcheck: true,
    allow_toggle: false,
    browsers: 'all',
    replace_tab_by_spaces: 2,
    EA_load_callback: 'fEALoaded',
  });
}
