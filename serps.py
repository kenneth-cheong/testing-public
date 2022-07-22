from selenium import webdriver
from webdriver_manager.chrome import ChromeDriverManager
from webdriver_manager.core.utils import ChromeType
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
import time
import pandas as pd
import collections

chrome_service = Service(ChromeDriverManager(chrome_type=ChromeType.CHROMIUM).install())

chrome_options = Options()
options = [
    "--headless",
    "--disable-gpu",
    "--window-size=1920,1200",
    "--ignore-certificate-errors",
    "--disable-extensions",
    "--no-sandbox",
    "--disable-dev-shm-usage"
]
for option in options:
    chrome_options.add_argument(option)

driver = webdriver.Chrome(service=chrome_service, options=chrome_options)

keywords = ['sem marketing']

targeted_url = 'mediaone'

ranking_list = []

for i in keywords:
    i.replace(' ','+')
    driver.get('https://www.google.com/search?q='+i+"&cr=countrysg&pws=0&num=30")
    time.sleep(10)

    domain = driver.find_element(By.XPATH,'//*[@id="rso"]').text
    print(domain)
    
    with open("Output.txt", "w") as text_file:
    text_file.write(domain)
