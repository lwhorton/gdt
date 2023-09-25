;(function () {

  function gatherForm(event) {
    const formElements = event.target.elements
    const formData = new FormData()
    for (let i = 0; i < formElements.length; i++) {
      const element = formElements[i];
      if (element.tagName.toLowerCase() === 'input' && element.type !== 'submit') {
        formData.append(element.name, element.value)
      }
    }

    const resource = event.target.action
    const method = event.target.method
    return { resource, method, formData }
  }

  async function submitForm({resource, method, formData}) {
    if (method.toLowerCase() === 'post')
      return await fetch(resource, {
        method: method,
        headers: {
          'content-type': 'application/x-www-form-urlencoded',
          'x-spinner': 'true'
        },
        body: new URLSearchParams(formData),
        redirect: "manual"
      })
    else if (method.toLowerCase() === 'get') {
      return await fetch(`${resource}?${new URLSearchParams(formData).toString()}`, {
        method: method,
        headers: {
          'x-spinner': 'true'
        },
        redirect: "manual"
      })
    } else {
      throw new Error('we dont support that form method yet, sadly')
    }
  }

  document.addEventListener('DOMContentLoaded', function() {
    const searchForm = document.querySelector('#search-form')
    if (!searchForm) {
      return
    }

    searchForm.addEventListener('submit', async function(event) {
      event.preventDefault()

      const spinner = document.querySelector('#search-form .spinner')
      spinner.style.visibility = 'visible'

      // dont tie our javascript to the form's implementation
      const {resource, method, formData} = gatherForm(event)
      const resp = await submitForm({resource, method, formData})

      // the things we do for perceived performance :(
      if (resp.status === 200 && resp.headers.get('location')) {
        window.location = `${window.location.origin}${resp.headers.get('location')}`
      }

      spinner.style.visibility = 'hidden'

      console.debug('resp', resp)
    })
  })
})();
